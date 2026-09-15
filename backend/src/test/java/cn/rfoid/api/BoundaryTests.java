package cn.rfoid.api;
import cn.rfoid.config.AppProperties;
import cn.rfoid.service.StorageService;
import cn.rfoid.service.DetectionValidator;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;

class BoundaryTests {
    @Test void explicitAllFramesAndLegacySamplingAreAccepted() {
        for(double value:List.of(0.0,.5,2.5,5.0)) assertEquals(value,DetectionValidator.sampleSeconds(value));
        for(double value:List.of(-1.0,.1,5.5,Double.NaN,Double.POSITIVE_INFINITY)) assertThrows(ApiException.class,()->DetectionValidator.sampleSeconds(value));
        assertEquals("逐帧检测",ReportController.sampling(0.0));
        assertEquals("每 2.5 秒一帧",ReportController.sampling(2.5));
    }
    @TempDir Path temp;
    StorageService storage() throws Exception { return new StorageService(new AppProperties(temp.toString(),"python","worker.py","model.pt",30,120,8,180)); }
    @Test void rejectPathTraversal() throws Exception { assertThrows(ApiException.class,()->storage().resolve("../private.txt")); }
    @Test void rejectMisleadingImageExtension() throws Exception {
        var file=new MockMultipartFile("file","photo.jpg","image/jpeg","not an image".getBytes());
        assertThrows(ApiException.class,()->storage().store(UUID.randomUUID().toString(),file));
    }
    @Test void rejectStreamingAndExecutableInputs() throws Exception {
        var file=new MockMultipartFile("file","stream.m3u8","text/plain","#EXTM3U".getBytes());
        assertThrows(ApiException.class,()->storage().store(UUID.randomUUID().toString(),file));
    }
    @Test void normalizeOriginalFilename() { assertEquals("photo.png",StorageService.cleanName("C:\\fakepath\\photo.png")); }
    @Test void rejectCrossedAndTinyRoi() {
        assertThrows(ApiException.class,()->DetectionValidator.roi(List.of(List.of(.1,.1),List.of(.9,.9),List.of(.1,.9),List.of(.9,.1))));
        assertThrows(ApiException.class,()->DetectionValidator.roi(List.of(List.of(.1,.1),List.of(.101,.1),List.of(.101,.101),List.of(.1,.101))));
    }
    @Test void acceptClockwiseAndCounterclockwiseRoi() {
        var roi=new ArrayList<>(List.of(List.of(.1,.1),List.of(.9,.1),List.of(.9,.9),List.of(.1,.9)));
        assertDoesNotThrow(()->DetectionValidator.roi(roi));Collections.reverse(roi);assertDoesNotThrow(()->DetectionValidator.roi(roi));
    }
    @Test void rejectNonFiniteModelValues() { assertThrows(ApiException.class,()->DetectionValidator.number(Double.NaN,0,1));assertThrows(ApiException.class,()->DetectionValidator.number(Double.POSITIVE_INFINITY,0,1)); }
    @Test void snapshotCannotExposeRequestOrLog() { assertThrows(IllegalArgumentException.class,()->DetectionValidator.checkAsset("request.json",temp));assertThrows(IllegalArgumentException.class,()->DetectionValidator.checkAsset("../../secret.jpg",temp)); }
    @Test void reportEscapesUserText() { assertEquals("&lt;script&gt;&amp;&quot;",ReportController.escape("<script>&\"")); }
    @Test void csvDisablesFormulaInjection() { assertTrue(ReportController.csv((Object)"=HYPERLINK(\"bad\")").startsWith("\"'=")); }
}
