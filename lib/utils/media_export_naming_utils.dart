import 'package:PiliPlus/models_new/download/bili_download_entry_info.dart';
import 'package:characters/characters.dart';

abstract final class MediaExportNamingUtils {
  static const defaultFallbackName = 'media';
  static const defaultAudioFallbackName = 'audio';
  static const defaultVideoFallbackName = 'video';

  static String sanitizeFileName(
    String input, {
    String fallback = defaultFallbackName,
  }) {
    final value = input
        .replaceAll(RegExp(r'[<>:/\\|?*"]'), ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
    return value.isEmpty ? fallback : value;
  }

  static String truncateTitle(
    String? input, {
    int maxChars = 8,
    String fallback = defaultFallbackName,
  }) {
    final value = sanitizeFileName(input ?? '', fallback: fallback);
    if (value == fallback) {
      return value;
    }
    final chars = value.characters;
    if (chars.length <= maxChars) {
      return value;
    }
    return chars.take(maxChars).toString();
  }

  static String shortCid(int cid, {int maxChars = 6}) {
    final value = cid.toString();
    final chars = value.characters;
    if (chars.length <= maxChars) {
      return value;
    }
    return chars.take(maxChars).toString();
  }

  static String buildBaseName({
    required String? title,
    required int cid,
    int maxTitleChars = 8,
    String fallback = defaultFallbackName,
  }) {
    final parts = <String>[
      truncateTitle(title, maxChars: maxTitleChars, fallback: fallback),
      'cid${shortCid(cid)}',
    ];
    return sanitizeFileName(
      parts.where((e) => e.trim().isNotEmpty).join(' - '),
      fallback: fallback,
    );
  }

  static String buildDownloadEntryBaseName(
    BiliDownloadEntryInfo entry, {
    int maxTitleChars = 8,
    String fallback = defaultVideoFallbackName,
  }) {
    return buildBaseName(
      title: entry.title,
      cid: entry.cid,
      maxTitleChars: maxTitleChars,
      fallback: fallback,
    );
  }
}
