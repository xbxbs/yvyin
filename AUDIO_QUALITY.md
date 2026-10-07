# 本地音质判定依据

## 采用的口径

Apple Music 官方用户指南：
https://support.apple.com/guide/music/listen-to-lossless-audio-mus90b573cbb/mac

该页面明确区分：Lossless（最高 24-bit / 48 kHz）与 Hi-Res Lossless（最高 24-bit / 192 kHz）。本应用标识统一为“无损”和“高解析度无损”，不再拼接SQ/HR或其他自创缩写；这些描述不是Apple认证，也不根据文件名、下载所选档位或曲目标题推断。本次仅改显示文案，判定规则不变。

- 已证实无损，源采样率不高于48kHz：普通无损。24-bit/44.1kHz与24-bit/48kHz不因“24bit”三个字自动升级HR。
- 已证实无损，源采样率高于48kHz：高解析度无损。源位深未提供时仍显示“位深未知”，不因为该字段缺失把已确认的高采样率降为SQ。
- 仅知道无损编码、采样率未知：只能确认无损，参数保留未知；不能确认HR。
- DSD单独标记；不套用Apple PCM分级说明。
- MP3/AAC等有损编码不授予无损徽标。仅MIME/扩展名/audio/raw解码输出不构成无损证据。

该规则描述文件参数，不保证原始录音/母带的解析度，也不代表Android混音器、蓝牙或输出设备当前使用同样参数。升采样文件不能仅凭标签识别；不伪称做过频谱/来源认证。

## 本轮修正

旧逻辑额外要求“源位深>16”，因此当Android没有报告位深时，即便已检测到96/192kHz无损也会落成SQ。这一不必要条件已移除。

源文件参数优先于播放器输出：FLAC STREAMINFO（含合法ID3前缀）、WAV/DSF/APE头；ALAC额外解析MediaExtractor的24字节ALACSpecificConfig或36字节alac atom。ID3长度与读取总量有界，取消可中断，不扫描任意音频字节猜测签名。

## 已做检查

tools/LocalAudioQualityCheck.kt 的离线生产代码检查涵盖截图24/44.1、24/48、24/96、16/96、未知位深192k、有损/假后缀、FLAC+ID3、ALAC两种配置及截断坏头。检查通过不是对用户全部音频的逐首验证；若某首仍有出入，需核对该文件头与另一播放器显示的实际位深/采样率，而非仅比“HR/SQ”文案。
