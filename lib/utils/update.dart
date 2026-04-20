import 'dart:ffi' show Abi;
import 'dart:io' show Platform;

import 'package:PiliPlus/build_config.dart';
import 'package:PiliPlus/common/constants.dart';
import 'package:PiliPlus/http/api.dart';
import 'package:PiliPlus/http/browser_ua.dart';
import 'package:PiliPlus/http/init.dart';
import 'package:PiliPlus/utils/accounts/account.dart';
import 'package:PiliPlus/utils/page_utils.dart';
import 'package:PiliPlus/utils/storage.dart';
import 'package:PiliPlus/utils/storage_key.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';

abstract final class Update {
  // 检查更新
  static Future<void> checkUpdate([bool isAuto = true]) async {
    if (kDebugMode) return;
    SmartDialog.dismiss();
    try {
      final res = await Request().get(
        Api.latestApp,
        options: Options(
          headers: {'user-agent': BrowserUa.mob},
          extra: {'account': const NoAccount()},
        ),
      );
      final Map<String, dynamic>? data = _normalizeReleaseData(res.data);
      if (data == null) {
        if (!isAuto) {
          SmartDialog.showToast('检查更新失败，GitHub接口未返回有效数据，请检查网络');
        }
        return;
      }
      final String releaseTime =
          (data['published_at'] ?? data['created_at'] ?? '').toString();
      if (releaseTime.isEmpty) {
        if (!isAuto) {
          SmartDialog.showToast('检查更新失败，未获取到发布时间');
        }
        return;
      }
      final int latest =
          DateTime.parse(releaseTime).millisecondsSinceEpoch ~/ 1000;
      if (BuildConfig.buildTime >= latest) {
        if (!isAuto) {
          SmartDialog.showToast('已是最新版本');
        }
      } else {
        SmartDialog.show(
          animationType: SmartAnimationType.centerFade_otherSlide,
          builder: (context) {
            final ThemeData theme = Theme.of(context);
            Widget downloadBtn(String text, {String? ext}) => TextButton(
              onPressed: () => onDownload(data, ext: ext),
              child: Text(text),
            );
            return AlertDialog(
              title: const Text('🎉 发现新版本 '),
              content: SizedBox(
                height: 280,
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${data['tag_name'] ?? data['name'] ?? 'latest'}',
                        style: const TextStyle(fontSize: 20),
                      ),
                      const SizedBox(height: 8),
                      Text('${data['body'] ?? ''}'),
                      TextButton(
                        onPressed: () => PageUtils.launchURL(
                          '${Constants.sourceCodeUrl}/commits/main',
                        ),
                        child: Text(
                          '点此查看完整更新(即commit)内容',
                          style: TextStyle(
                            color: theme.colorScheme.primary,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              actions: [
                if (isAuto)
                  TextButton(
                    onPressed: () {
                      SmartDialog.dismiss();
                      GStorage.setting.put(SettingBoxKey.autoUpdate, false);
                    },
                    child: Text(
                      '不再提醒',
                      style: TextStyle(
                        color: theme.colorScheme.outline,
                      ),
                    ),
                  ),
                TextButton(
                  onPressed: SmartDialog.dismiss,
                  child: Text(
                    '取消',
                    style: TextStyle(
                      color: theme.colorScheme.outline,
                    ),
                  ),
                ),
                if (Platform.isWindows) ...[
                  downloadBtn('zip', ext: 'zip'),
                  downloadBtn('exe', ext: 'exe'),
                ] else if (Platform.isLinux) ...[
                  downloadBtn('rpm', ext: 'rpm'),
                  downloadBtn('deb', ext: 'deb'),
                  downloadBtn('targz', ext: 'tar.gz'),
                ] else
                  downloadBtn('下载'),
              ],
            );
          },
        );
      }
    } catch (e) {
      if (kDebugMode) debugPrint('failed to check update: $e');
    }
  }

  static Map<String, dynamic>? _normalizeReleaseData(dynamic raw) {
    if (raw == null) return null;
    if (raw is Map) {
      return Map<String, dynamic>.from(raw);
    }
    if (raw is List && raw.isNotEmpty) {
      final dynamic first = raw.first;
      if (first is Map) {
        return Map<String, dynamic>.from(first);
      }
    }
    return null;
  }

  static String? _abiToAssetName(Abi abi) {
    switch (abi) {
      case Abi.androidArm64:
        return 'arm64-v8a';
      case Abi.androidArm:
        return 'armeabi-v7a';
      case Abi.androidX64:
        return 'x86_64';
      case Abi.androidIA32:
        return 'x86';
      default:
        return null;
    }
  }

  static String? _findAssetUrl(
    List<Map<String, dynamic>> assets,
    List<String> keywords, {
    String? ext,
  }) {
    for (final keyword in keywords) {
      for (final asset in assets) {
        final String name = (asset['name'] ?? '').toString().toLowerCase();
        final bool extOk =
            ext == null || ext.isEmpty ? true : name.endsWith(ext.toLowerCase());
        if (extOk && name.contains(keyword.toLowerCase())) {
          return asset['browser_download_url']?.toString();
        }
      }
    }
    return null;
  }

  // 下载适用于当前系统的安装包
  static Future<void> onDownload(Map data, {String? ext}) async {
    SmartDialog.dismiss();
    try {
      final List<dynamic> rawAssets = data['assets'] as List<dynamic>? ?? <dynamic>[];
      final List<Map<String, dynamic>> assets = rawAssets
          .whereType<Map>()
          .map((e) => Map<String, dynamic>.from(e))
          .toList();
      if (assets.isEmpty) {
        throw UnsupportedError('release assets empty');
      }

      String? url;
      if (Platform.isAndroid) {
        final AndroidDeviceInfo androidInfo = await DeviceInfoPlugin().androidInfo;
        final String? runtimeAbi = _abiToAssetName(Abi.current());
        final List<String> keywords = <String>[
          if (runtimeAbi != null) runtimeAbi,
          ...androidInfo.supportedAbis,
          'release.apk',
          '.apk',
        ];
        url = _findAssetUrl(assets, keywords, ext: ext ?? 'apk');
      } else {
        url = _findAssetUrl(
          assets,
          <String>[Platform.operatingSystem],
          ext: ext,
        );
      }

      if (url == null || url.isEmpty) {
        throw UnsupportedError('platform asset not found');
      }
      PageUtils.launchURL(url);
    } catch (e) {
      if (kDebugMode) debugPrint('download error: $e');
      PageUtils.launchURL('${Constants.sourceCodeUrl}/releases/latest');
    }
  }
}
