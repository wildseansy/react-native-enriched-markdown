#pragma once

#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Horizontal indent added per list nesting depth (points).
static const CGFloat ENRMListIndentPerDepth = 18.0;

/// Width reserved for the bullet marker column before the item text (points).
static const CGFloat ENRMListMarkerWidth = 18.0;

/// Maximum supported list nesting depth (0-based), so indentation stays sane.
static const NSInteger ENRMMaxListDepth = 5;

/// Vertical gap added after each list item (points) so bullets read as visually
/// separate rows rather than a dense block.
static const CGFloat ENRMListItemSpacing = 6.0;

/// Paragraph-level block kinds supported by the editor. Unlike inline styles
/// (bold, italic) which apply to character ranges, block styles apply to whole
/// lines and are mutually exclusive per line.
typedef NS_ENUM(NSInteger, ENRMInputBlockType) {
  ENRMInputBlockTypeParagraph = 0,
  ENRMInputBlockTypeHeading1,
  ENRMInputBlockTypeHeading2,
  ENRMInputBlockTypeHeading3,
  ENRMInputBlockTypeUnorderedListItem,
};

/// Custom attributed-string attribute storing the line's ENRMInputBlockType
/// (boxed NSNumber). TextKit migrates attributes across edits, so the block kind
/// survives typing, deletion, and paste without manual range bookkeeping — the
/// same reason inline marks aren't re-derived on every keystroke.
extern NSAttributedStringKey const ENRMBlockTypeAttributeName;

/// Nesting depth (0-based) for list items, boxed as NSNumber. Absent / 0 for
/// top-level items and non-list lines.
extern NSAttributedStringKey const ENRMListDepthAttributeName;

/// Heading level (1-3) for a heading block type, or 0 for non-headings.
static inline NSInteger ENRMHeadingLevelForBlockType(ENRMInputBlockType type)
{
  switch (type) {
    case ENRMInputBlockTypeHeading1:
      return 1;
    case ENRMInputBlockTypeHeading2:
      return 2;
    case ENRMInputBlockTypeHeading3:
      return 3;
    default:
      return 0;
  }
}

static inline ENRMInputBlockType ENRMBlockTypeForHeadingLevel(NSInteger level)
{
  switch (level) {
    case 1:
      return ENRMInputBlockTypeHeading1;
    case 2:
      return ENRMInputBlockTypeHeading2;
    case 3:
      return ENRMInputBlockTypeHeading3;
    default:
      return ENRMInputBlockTypeParagraph;
  }
}

/// A line range tagged with its block type and depth, used to hand block
/// structure to the serializer (otherwise stateless: plain text + inline ranges).
@interface ENRMBlockRange : NSObject

@property (nonatomic, assign) NSRange range;
@property (nonatomic, assign) ENRMInputBlockType type;
@property (nonatomic, assign) NSInteger depth;

+ (instancetype)rangeWithType:(ENRMInputBlockType)type depth:(NSInteger)depth range:(NSRange)range;

@end

NS_ASSUME_NONNULL_END
