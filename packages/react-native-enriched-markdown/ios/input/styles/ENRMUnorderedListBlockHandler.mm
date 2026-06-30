#import "ENRMUnorderedListBlockHandler.h"
#import "ENRMInputBlockType.h"

@implementation ENRMUnorderedListBlockHandler

- (ENRMInputBlockType)blockType
{
  return ENRMInputBlockTypeUnorderedListItem;
}

- (BOOL)continuesOnNewline
{
  return YES;
}

- (void)applyAttributesToParagraphStyle:(NSMutableParagraphStyle *)paragraphStyle
                             attributes:(NSMutableDictionary<NSAttributedStringKey, id> *)attributes
                             blockRange:(ENRMBlockRange *)blockRange
                                  style:(ENRMInputFormatterStyle *)style
{
  NSInteger depth = blockRange.level;
  if (depth < 0) {
    depth = 0;
  } else if (depth > kENRMMaxListDepth) {
    depth = kENRMMaxListDepth;
  }

  // Reserve a marker column per nesting level. Both the first (marker) line and
  // wrapped continuation lines align to the same text inset so wrapped text
  // hangs under the text, not under the bullet.
  CGFloat indent = (depth + 1) * kENRMListIndentPerDepth;
  paragraphStyle.firstLineHeadIndent = indent;
  paragraphStyle.headIndent = indent;
  paragraphStyle.paragraphSpacingBefore = style.listItemSpacing;
}

- (NSString *)markdownLinePrefixForBlockRange:(ENRMBlockRange *)blockRange
{
  NSInteger depth = blockRange.level;
  if (depth < 0) {
    depth = 0;
  } else if (depth > kENRMMaxListDepth) {
    depth = kENRMMaxListDepth;
  }
  // Two spaces of indent per nesting level, then the bullet marker, e.g.
  // "    - " for a depth-2 item.
  NSString *indent = [@"" stringByPaddingToLength:depth * 2 withString:@" " startingAtIndex:0];
  return [indent stringByAppendingString:@"- "];
}

- (BOOL)matchesMd4cBlockType:(MD_BLOCKTYPE)md4cType detail:(void *)detail outLevel:(NSInteger *)outLevel
{
  // The line-level block is the list item. Nesting depth comes from how many
  // MD_BLOCK_UL ancestors enclose it, which the parser tracks during traversal
  // and writes into the block's level — md4c's MD_BLOCK_LI_DETAIL carries no
  // depth — so the handler claims the item and leaves level to the parser.
  if (md4cType != MD_BLOCK_LI) {
    return NO;
  }
  *outLevel = 0;
  return YES;
}

@end
