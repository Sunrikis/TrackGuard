package cn.rfoid.service;
import cn.rfoid.api.ApiException;

import java.nio.file.*;
import java.util.*;

/** Validate the model boundary before any result is persisted or served. */
public final class DetectionValidator {
    private DetectionValidator() {}
    public static List<String> categories(List<String> categories) {
        if (categories == null || categories.isEmpty() || categories.size() > 5 || categories.stream().anyMatch(Objects::isNull)
            || !Set.of("person", "vehicle", "motorcycle", "animal", "obstacle").containsAll(categories)
            || new HashSet<>(categories).size() != categories.size())
            throw ApiException.invalid("请至少选择一种有效的识别目标，且不可重复");
        return List.copyOf(categories);
    }
    public static double number(Object value, double min, double max) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<min || n.doubleValue()>max)
            throw ApiException.invalid("检测参数或模型数值超出有效范围");
        return n.doubleValue();
    }
    public static double sampleSeconds(double value) {
        if (value == 0) return 0; // Explicit all-frames mode, kept in the existing persisted parameter.
        return number(value, .5, 5);
    }
    public static void roi(List<List<Double>> points) {
        if (points.isEmpty()) return;
        if (points.size()<4 || points.size()>24) throw ApiException.invalid("危险区需要按顺序标定 4 至 24 个边界点");
        double area=0;
        int count=points.size();
        for (int i=0;i<count;i++) {
            List<Double> p=points.get(i),q=points.get((i+1)%count);
            if (p.size()!=2 || q.size()!=2) throw ApiException.invalid("危险区坐标格式错误");
            number(p.get(0),0,1);number(p.get(1),0,1);
            if (Math.hypot(p.get(0)-q.get(0),p.get(1)-q.get(1))<0.00001)
                throw ApiException.invalid("危险区含有重复边界点");
            area+=p.get(0)*q.get(1)-q.get(0)*p.get(1);
            for (int j=i+1;j<count;j++) {
                if (j==i+1 || (i==0 && j==count-1)) continue;
                if (segmentsIntersect(p,q,points.get(j),points.get((j+1)%count)))
                    throw ApiException.invalid("危险区边界不能交叉，请沿同一方向重新标定");
            }
        }
        if (Math.abs(area)/2<0.005) throw ApiException.invalid("危险区面积过小，请重新标定");
    }
    private static double orientation(List<Double> a,List<Double> b,List<Double> c) {
        return (b.get(0)-a.get(0))*(c.get(1)-a.get(1))-(b.get(1)-a.get(1))*(c.get(0)-a.get(0));
    }
    private static boolean segmentsIntersect(List<Double> a,List<Double> b,List<Double> c,List<Double> d) {
        double epsilon=1e-7;
        double abC=orientation(a,b,c),abD=orientation(a,b,d),cdA=orientation(c,d,a),cdB=orientation(c,d,b);
        if (Math.abs(abC)<=epsilon && within(c,a,b,epsilon)) return true;
        if (Math.abs(abD)<=epsilon && within(d,a,b,epsilon)) return true;
        if (Math.abs(cdA)<=epsilon && within(a,c,d,epsilon)) return true;
        if (Math.abs(cdB)<=epsilon && within(b,c,d,epsilon)) return true;
        return abC*abD<0 && cdA*cdB<0;
    }
    private static boolean within(List<Double> p,List<Double> a,List<Double> b,double epsilon) {
        return p.get(0)>=Math.min(a.get(0),b.get(0))-epsilon && p.get(0)<=Math.max(a.get(0),b.get(0))+epsilon
            && p.get(1)>=Math.min(a.get(1),b.get(1))-epsilon && p.get(1)<=Math.max(a.get(1),b.get(1))+epsilon;
    }
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> result(Map<String,Object> result, Path runDir) {
        if (!(result.get("detections") instanceof List<?> list) || list.size()>200) throw new IllegalArgumentException("Invalid detection list");
        if (!(result.get("region") instanceof List<?> region)) throw new IllegalArgumentException("Missing track region");
        roi(region.stream().map(p->((List<?>)p).stream().map(v->number(v,0,1)).toList()).toList());
        double frames=number(result.get("sampledFrames"),1,14400);
        if (frames != Math.rint(frames)) throw new IllegalArgumentException("Invalid frame count");
        number(result.get("inferenceMs"),0,3_600_000);
        Set<String> keys=new HashSet<>();
        for (Object item:list) {
            if (!(item instanceof Map<?,?> d)) throw new IllegalArgumentException("Invalid detection");
            if (!Set.of("person","vehicle","motorcycle","animal","obstacle").contains(d.get("category"))) throw new IllegalArgumentException("Invalid category");
            if (!Set.of("HIGH","MEDIUM","INFO").contains(d.get("risk")) || !(d.get("inDanger") instanceof Boolean)) throw new IllegalArgumentException("Invalid risk");
            if (Boolean.TRUE.equals(d.get("inDanger")) == "INFO".equals(d.get("risk"))) throw new IllegalArgumentException("Inconsistent risk");
            number(d.get("confidence"),0,1);number(d.get("frameTime"),0,120);
            if (!(d.get("box") instanceof List<?> box) || box.size()!=4) throw new IllegalArgumentException("Invalid box");
            for (Object n:box) number(n,0,1);
            if (((Number)box.get(0)).doubleValue()>=((Number)box.get(2)).doubleValue() || ((Number)box.get(1)).doubleValue()>=((Number)box.get(3)).doubleValue()) throw new IllegalArgumentException("Empty box");
            String key=Objects.toString(d.get("trackKey"),"");
            if (!key.matches("[a-z0-9-]{1,40}") || !keys.add(key)) throw new IllegalArgumentException("Invalid track key");
            for (String field:List.of("label","advice")) {
                if (!(d.get(field) instanceof String s) || s.isBlank() || s.length()>(field.equals("label")?60:500)) throw new IllegalArgumentException("Invalid text");
            }
            Object source=d.containsKey("adviceSource")?d.get("adviceSource"):"LOCAL_RULE";
            if (!Set.of("LOCAL_RULE","DEEPSEEK").contains(source)) throw new IllegalArgumentException("Invalid advice source");
            checkAsset(Objects.toString(d.get("snapshot"),""),runDir);
        }
        checkAsset(Objects.toString(result.get("preview"),""),runDir);
        return (List<Map<String,Object>>)(List<?>)list;
    }
    public static void checkAsset(String name, Path runDir) {
        if (!name.matches("frame-[0-9]{6}\\.jpg") || !Files.isRegularFile(runDir.resolve(name))) throw new IllegalArgumentException("Invalid snapshot");
    }
}
