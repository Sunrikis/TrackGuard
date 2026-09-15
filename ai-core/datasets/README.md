# 训练数据集

将自己准备的图片和 YOLO 检测标注放入 `railway/`。图片和标签不随 Git 仓库分发；本机整理的数据可直接通过 `railway/data.yaml` 使用。

## SynRailObs 均衡子集

`train/prepare_dataset.py` 可从本机 `datasets/SynRailObs` 下载目录整理数据，默认输出训练集每类 1,000 张、验证集每类 200 张，共 6,000 对图片和标签。保持原训练/验证划分，只选择带完整单目标标注的图片，不删除图中的其他目标标签。抽样前检查类别和框坐标，选中的图片完整解码，并按文件 SHA-256 去除训练集和验证集之间以及各集合内部的完全重复图片。固定种子为 `20260917`。

| ID | 原类别 | 输出类别 | 业务类型 |
| --- | --- | --- | --- |
| 0 | animals | animal | 动物 |
| 1 | motos | motorcycle | 摩托车 |
| 2 | persons | person | 人员 |
| 3 | rocks | large rock | 岩石 / 障碍物 |
| 4 | vehicles | vehicle | 车辆 |

每张选中图片对应一个目标框，因此每类图片数、非空标签文件数和目标框数均相同。原始数据保持不变，缺少标注或不合格的数据不会用于训练。整理结果中的 `preparation.json` 记录原始分布、排除原因、逐图来源与哈希，不提交 Git。完全重复检查不能保证没有相似背景或近似重复场景。

在仓库根目录执行：

```powershell
# 首次准备（已有数据时拒绝覆盖）
python .\ai-core\train\prepare_dataset.py

# 输出另一种规模，必须使用新的目录
python .\ai-core\train\prepare_dataset.py --train-per-class 1500 --val-per-class 300 --output .\ai-core\datasets\railway-larger

# 检查默认数据集
python .\ai-core\train\train.py --mode check

# 在已整理的数据上补充独立测试集，每类 100 张；不会更改训练/验证图片
python .\ai-core\train\prepare_dataset.py --add-test --test-per-class 100 --seed 20260918

# 同时检查训练、验证和测试集
python .\ai-core\train\train.py --mode check --split test
```

补充测试集后，训练集每类 1,000 张、验证集每类 200 张、测试集每类 100 张，共 6,500 张。测试样本来自原始验证池中未使用的图片，并与现有训练、验证集按文件 SHA-256 排重，保留完整标注；不移动现有样本。该检查排除完全重复文件，不能保证没有相似场景。测试集只用于模型定型后的最终评估，不用于选择权重或调整参数。

使用训练产出的权重评估测试集：

```powershell
python .\ai-core\train\train.py --mode val --split test --model '.\ai-core\train\runs\<运行目录>\train\weights\best.pt' --device 0
```

## 目录与标注格式

```text
railway/
├── data.yaml
├── images/
│   ├── train/    # 训练图片
│   ├── val/      # 验证图片
│   └── test/     # 可选：独立测试图片
└── labels/
    ├── train/    # 对应训练标注
    ├── val/      # 对应验证标注
    └── test/     # 对应测试标注
```

例如 `images/train/track001.jpg` 对应 `labels/train/track001.txt`。支持 JPG、JPEG、PNG、BMP、WEBP 图片。视频需要先提取图片，再标注检测框。

每个目标占一行，使用空格分隔：

```text
类别编号 中心点x 中心点y 宽度 高度
```

标注文件内只写数字，例如当前五类配置中的人员框：

```text
2 0.50 0.60 0.10 0.30
```

坐标和宽高均相对于整张图片归一化到 0–1，宽高必须大于 0，框不能超出图片边界。没有目标的背景图也请放置同名空 `.txt`，以便检查器区分背景图与漏标文件。

`data.yaml` 是类别编号的唯一依据，整理后的配置使用上述五类。你可以只保留需要的类别，但必须同步修改全部标注中的编号，编号从 0 连续递增。不要直接把已有数据集的类别编号套用到其他配置。若添加其他名称，需要同时更新 `ai-core/worker.py` 的 `GROUPS` 映射，否则软件不会识别其业务类别。

训练集和验证集都必须有图片和目标标注；测试集可暂时留空，使用时在 `data.yaml` 中添加 `test: images/test`。建议按线路、机位或原始视频划分集合，避免同一视频相邻帧分散到不同集合造成数据泄漏。手动圈定的轨道危险区与这里的目标检测框不同，不要把危险区四边形作为目标框标签。

图片、标注和数据缓存默认被 Git 忽略，目录模板及配置可提交。训练命令见 [训练说明](../train/README.md)。
