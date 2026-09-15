# 模型训练与微调

使用项目已有 Python 环境和 `ai-core/requirements.txt`。入口采用 Ultralytics `YOLOWorld`，默认基于原始 `yolov8l-worldv2.pt` 微调；不会改写输入权重或自动替换线上模型。所有命令从仓库根目录执行。

## 数据准备

把图片和目标框标签放入 [数据集目录](../datasets/README.md)，核对 `ai-core/datasets/railway/data.yaml` 的类别与标注编号。对于 SynRailObs，可使用本目录的 `prepare_dataset.py` 生成每类数量相等的精简子集；图片与目标框均按完整样本保留，说明见数据集文档。先检查数据：

```powershell
python .\ai-core\train\train.py --mode check
```

检查器验证训练集、验证集非空，同名标注存在、类别编号和归一化框坐标有效。检查通过不代表标注质量、图片解码或模型精度已经通过评估。默认只支持 `images/<集合名>` 与对应 `labels/<集合名>` 目录，可递归包含子目录。

## 微调预训练模型

```powershell
python .\ai-core\train\train.py --epochs 100 --batch 4 --device 0
```

没有 CUDA GPU 时改为 `--device cpu`。显存不足时先降低 `--batch`；省略 `--device` 时由 Ultralytics 选择设备。Windows 默认 `--workers 0`。可用 `--freeze 10` 冻结前 10 层进行微调。

指定其他 YOLO-World 权重或数据配置：

```powershell
python .\ai-core\train\train.py `
  --model .\ai-core\models\trackguard-world.pt `
  --data .\ai-core\datasets\railway\data.yaml `
  --epochs 50 --batch 4 --imgsz 640 --device 0
```

训练时类别由数据集配置决定。默认原始预训练权重更适合作为可复现的起点；也可以继续微调自己的 YOLO-World `best.pt`。此入口针对 YOLO-World，不接受其他架构的检查点。

## 从零训练与断点续训

随机初始化 YOLO-World L 检测网络，使用内置架构配置：

```powershell
python .\ai-core\train\train.py --scratch --epochs 200 --batch 4 --device 0
```

`--scratch` 不加载检测预训练权重，YOLO-World 仍使用预训练 CLIP 文本编码器，因此不是视觉和语言全部从零训练。首次训练可能需要下载文本编码器；少量数据通常适合从预训练模型微调。

续训被中断的运行（将路径中的 `<运行目录>` 替换为实际目录）：

```powershell
python .\ai-core\train\train.py --resume '.\ai-core\train\runs\<运行目录>\train\weights\last.pt' --device 0
```

续训恢复检查点保存的轮数、优化器与训练配置，不采用命令行中的新数据或轮数。完成训练后的进一步微调使用 `--model best.pt` 开始新运行。续训依赖原运行目录中的配置，请保留整个运行目录。

## 评估与接入软件

每次训练保存到独立的 `runs/<时间戳>/train/`，主要输出包括 `weights/best.pt`、`weights/last.pt`、指标和训练曲线。`data.resolved.yaml` 保存该次使用的数据路径及类别，结果目录默认不提交 Git。

```powershell
python .\ai-core\train\train.py --mode val `
  --model '.\ai-core\train\runs\<运行目录>\train\weights\best.pt' --device 0
```

独立测试集评估：可通过 `prepare_dataset.py --add-test --test-per-class 100 --seed 20260918` 从未使用的原始图片中补充每类数量相同的测试集，自动配置 `test: images/test`。执行 `python .\ai-core\train\train.py --mode check --split test` 检查三个集合；给上述评估命令加上 `--split test` 即可评估。不要用测试集选择模型或调参。

评估完成后，手动将 `best.pt` 复制为模型目录中的新文件名，在模型目录的 `catalog.json` 中添加条目，配置支持的业务类别。需要设为默认模型时更新 `RFOID_MODEL` 并重启后端。保留原权重以便比较。自定义类别名称必须与 `worker.py` 的 `GROUPS` 对应；SynRailObs 子集的五类名称已经兼容。

这里训练的是目标检测模型，不训练轨道危险区定位；当前危险区仍由几何估计或人工标定提供。

参考：[Ultralytics YOLO-World](https://docs.ultralytics.com/models/yolo-world/)。
