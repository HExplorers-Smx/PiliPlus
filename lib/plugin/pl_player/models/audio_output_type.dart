import 'package:PiliPlus/models/common/enum_with_label.dart';

enum AudioOutput implements EnumWithLabel {
  opensles('OpenSL ES'),
  aaudio('AAudio'),
  audiotrack('AudioTrack'),
  ;

  static const legacyDefaultValue = 'opensles,aaudio,audiotrack';
  static const defaultValue = 'audiotrack,aaudio,opensles';

  @override
  final String label;
  const AudioOutput(this.label);
}
