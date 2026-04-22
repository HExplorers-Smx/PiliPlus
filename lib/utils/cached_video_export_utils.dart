import 'dart:io';

import 'package:PiliPlus/models_new/download/bili_download_entry_info.dart';
import 'package:PiliPlus/utils/extension/file_ext.dart';
import 'package:PiliPlus/utils/media_export_naming_utils.dart';
import 'package:PiliPlus/utils/path_utils.dart';
import 'package:PiliPlus/utils/utils.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:path/path.dart' as path;

abstract final class CachedVideoExportUtils {
  static const _exportDirName = 'video_export';

  static String _buildBaseName(BiliDownloadEntryInfo entry) {
    return MediaExportNamingUtils.buildDownloadEntryBaseName(entry);
  }

  static Future<String> _buildUniquePath(String dirPath, String fileName) async {
    final ext = path.extension(fileName);
    final baseName = path.basenameWithoutExtension(fileName);
    var targetPath = path.join(dirPath, fileName);
    var index = 1;
    while (File(targetPath).existsSync()) {
      targetPath = path.join(dirPath, '$baseName ($index)$ext');
      index++;
    }
    return targetPath;
  }

  static Future<void> exportEntryToMp4(BiliDownloadEntryInfo entry) async {
    if (!entry.isCompleted) {
      SmartDialog.showToast('请先等待缓存完成');
      return;
    }
    if (entry.typeTag == null || entry.typeTag!.isEmpty) {
      SmartDialog.showToast('当前缓存缺少视频索引信息');
      return;
    }

    final mediaDir = Directory(path.join(entry.entryDirPath, entry.typeTag!));
    if (!mediaDir.existsSync()) {
      SmartDialog.showToast('未找到缓存媒体文件');
      return;
    }

    final directMp4 = File(path.join(mediaDir.path, PathUtils.videoNameType1));
    final dashVideo = File(path.join(mediaDir.path, PathUtils.videoNameType2));
    final dashAudio = File(path.join(mediaDir.path, PathUtils.audioNameType2));

    final exportDir = Directory(path.join(downloadPath, _exportDirName));
    if (!exportDir.existsSync()) {
      await exportDir.create(recursive: true);
    }
    final outputPath = await _buildUniquePath(
      exportDir.path,
      '${_buildBaseName(entry)}.mp4',
    );

    try {
      SmartDialog.showLoading(msg: '正在导出MP4');
      if (directMp4.existsSync() && !dashVideo.existsSync()) {
        await directMp4.copy(outputPath);
      } else {
        if (!dashVideo.existsSync()) {
          throw Exception('未找到可导出的缓存视频文件');
        }
        if (!Platform.isAndroid) {
          throw Exception('当前仅支持 Android 合并缓存视频为 MP4');
        }
        await Utils.channel.invokeMethod('exportCachedVideoToMp4', {
          'videoPath': dashVideo.path,
          'audioPath': dashAudio.existsSync() ? dashAudio.path : null,
          'outputPath': outputPath,
        });
      }
      SmartDialog.dismiss();
      SmartDialog.showToast('视频已导出为MP4');
    } catch (e, s) {
      SmartDialog.dismiss();
      await File(outputPath).tryDel();
      Utils.reportError(e, s);
      if (kDebugMode) {
        debugPrint('export cached video error: $e\n\n$s');
      }
      SmartDialog.showToast('导出MP4失败: $e');
    }
  }
}
