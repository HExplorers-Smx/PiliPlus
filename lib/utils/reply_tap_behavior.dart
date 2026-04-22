enum ReplyTapArea {
  mainReply,
  previewReplies,
  secondLevelReply,
  showMore,
}

enum ReplyTapAction {
  reply,
  openSecondLevel,
}

final class ReplyTapBehavior {
  const ReplyTapBehavior({
    this.mainReply = ReplyTapAction.reply,
    this.previewReplies = ReplyTapAction.openSecondLevel,
    this.secondLevelReply = ReplyTapAction.reply,
    this.showMore = ReplyTapAction.openSecondLevel,
  });

  final ReplyTapAction mainReply;
  final ReplyTapAction previewReplies;
  final ReplyTapAction secondLevelReply;
  final ReplyTapAction showMore;

  ReplyTapAction actionOf(ReplyTapArea area) {
    return switch (area) {
      ReplyTapArea.mainReply => mainReply,
      ReplyTapArea.previewReplies => previewReplies,
      ReplyTapArea.secondLevelReply => secondLevelReply,
      ReplyTapArea.showMore => showMore,
    };
  }

  static const videoDefault = ReplyTapBehavior();
}
