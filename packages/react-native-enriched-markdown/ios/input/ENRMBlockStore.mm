#import "ENRMBlockStore.h"

static NSUInteger sortedInsertionIndex(NSArray<ENRMBlockRange *> *ranges, NSUInteger location)
{
  NSUInteger index = 0;
  for (ENRMBlockRange *existing in ranges) {
    if (existing.range.location > location)
      break;
    index++;
  }
  return index;
}

static void removeIndexesInReverse(NSMutableArray *array, NSMutableIndexSet *indexes)
{
  [indexes enumerateIndexesWithOptions:NSEnumerationReverse
                            usingBlock:^(NSUInteger idx, BOOL *stop) { [array removeObjectAtIndex:idx]; }];
}

typedef NS_ENUM(NSInteger, EditOverlap) {
  EditOverlapBeforeEdit,
  EditOverlapAfterEdit,
  EditOverlapFullyDeleted,
  EditOverlapDeletedInside,
  EditOverlapClippedEnd,
  EditOverlapClippedStart,
};

static EditOverlap classifyOverlap(NSUInteger rangeStart, NSUInteger rangeEnd, NSUInteger editLocation,
                                   NSUInteger deleteEnd)
{
  if (rangeEnd <= editLocation)
    return EditOverlapBeforeEdit;
  if (rangeStart >= deleteEnd)
    return EditOverlapAfterEdit;
  if (rangeStart >= editLocation && rangeEnd <= deleteEnd)
    return EditOverlapFullyDeleted;
  if (rangeStart < editLocation && rangeEnd > deleteEnd)
    return EditOverlapDeletedInside;
  if (rangeStart < editLocation && rangeEnd <= deleteEnd)
    return EditOverlapClippedEnd;
  return EditOverlapClippedStart;
}

/// Expands a selection to cover whole paragraphs (line-scoped block boundaries).
static NSRange paragraphBoundsForRange(NSRange range, NSString *text)
{
  if (text.length == 0) {
    return NSMakeRange(0, 0);
  }
  NSRange clamped = NSIntersectionRange(range, NSMakeRange(0, text.length));
  if (clamped.length == 0 && range.location <= text.length) {
    clamped.location = MIN(range.location, text.length);
  }
  return [text paragraphRangeForRange:clamped];
}

@implementation ENRMBlockStore {
  NSMutableArray<ENRMBlockRange *> *_ranges;
}

- (instancetype)init
{
  if (self = [super init]) {
    _ranges = [NSMutableArray array];
  }
  return self;
}

- (NSArray<ENRMBlockRange *> *)allRanges
{
  return [_ranges copy];
}

- (void)setRanges:(NSArray<ENRMBlockRange *> *)ranges
{
  _ranges = [[ranges sortedArrayUsingComparator:^NSComparisonResult(ENRMBlockRange *first, ENRMBlockRange *second) {
    if (first.range.location < second.range.location)
      return NSOrderedAscending;
    if (first.range.location > second.range.location)
      return NSOrderedDescending;
    return NSOrderedSame;
  }] mutableCopy];
}

- (void)clearAll
{
  [_ranges removeAllObjects];
}

- (nullable ENRMBlockRange *)blockRangeContainingPosition:(NSUInteger)position
{
  for (ENRMBlockRange *blockRange in _ranges) {
    if (position >= blockRange.range.location && position < NSMaxRange(blockRange.range)) {
      return blockRange;
    }
  }
  return nil;
}

/// Drops any stored block overlapping `paragraphRange` so a replacement can be
/// inserted cleanly. Blocks are line-scoped and never partially overlap, so a
/// touched block is removed wholesale.
- (void)removeBlocksOverlappingRange:(NSRange)paragraphRange
{
  NSUInteger removeStart = paragraphRange.location;
  NSUInteger removeEnd = NSMaxRange(paragraphRange);
  NSMutableIndexSet *indexesToRemove = [NSMutableIndexSet indexSet];

  for (NSUInteger idx = 0; idx < _ranges.count; idx++) {
    ENRMBlockRange *existing = _ranges[idx];
    NSUInteger existingStart = existing.range.location;
    NSUInteger existingEnd = NSMaxRange(existing.range);

    // A zero-length block (an empty-line heading anchor) occupies a point, not a
    // span, so the half-open overlap test never matches it. Match it explicitly
    // when its anchor lies within the (possibly zero-length) removal range — the
    // empty heading line being targeted.
    if (existing.range.length == 0) {
      if (existingStart >= removeStart && existingStart <= removeEnd) {
        [indexesToRemove addIndex:idx];
      }
      continue;
    }

    if (existingEnd <= removeStart || existingStart >= removeEnd) {
      continue;
    }
    [indexesToRemove addIndex:idx];
  }

  removeIndexesInReverse(_ranges, indexesToRemove);
}

- (void)setBlockType:(ENRMInputBlockType)type
                level:(NSInteger)level
    forParagraphRange:(NSRange)range
               inText:(NSString *)text
{
  NSRange paragraphRange = paragraphBoundsForRange(range, text);
  [self removeBlocksOverlappingRange:paragraphRange];

  // An empty line has a zero-length paragraph range. Headings and bullet items
  // persist on an empty line via a zero-length anchor (the line stays the block);
  // other block types have nothing to anchor, so skip them.
  if (paragraphRange.length == 0 && !ENRMBlockTypePersistsWhenEmpty(type)) {
    return;
  }

  ENRMBlockRange *blockRange = [ENRMBlockRange rangeWithType:type range:paragraphRange level:level];
  NSUInteger insertAt = sortedInsertionIndex(_ranges, blockRange.range.location);
  [_ranges insertObject:blockRange atIndex:insertAt];
}

- (void)removeBlockInParagraphRange:(NSRange)range inText:(NSString *)text
{
  NSRange paragraphRange = paragraphBoundsForRange(range, text);
  [self removeBlocksOverlappingRange:paragraphRange];
}

- (void)removeBlock:(ENRMBlockRange *)block
{
  NSUInteger idx = [_ranges indexOfObjectIdenticalTo:block];
  if (idx != NSNotFound) {
    [_ranges removeObjectAtIndex:idx];
  }
}

- (void)adjustForEditAtLocation:(NSUInteger)editLocation
                  deletedLength:(NSUInteger)deletedLength
                 insertedLength:(NSUInteger)insertedLength
{
  if (deletedLength == 0 && insertedLength == 0)
    return;

  NSUInteger deleteEnd = editLocation + deletedLength;
  NSMutableIndexSet *indexesToRemove = [NSMutableIndexSet indexSet];

  for (NSUInteger idx = 0; idx < _ranges.count; idx++) {
    ENRMBlockRange *blockRange = _ranges[idx];
    NSUInteger rangeStart = blockRange.range.location;
    NSUInteger rangeEnd = NSMaxRange(blockRange.range);

    if (deletedLength > 0) {
      EditOverlap overlap = classifyOverlap(rangeStart, rangeEnd, editLocation, deleteEnd);

      switch (overlap) {
        case EditOverlapBeforeEdit:
          break;

        case EditOverlapAfterEdit:
          blockRange.range = NSMakeRange(rangeStart - deletedLength + insertedLength, blockRange.range.length);
          break;

        case EditOverlapFullyDeleted:
          // Persisting blocks (headings, bullet items) survive as an empty line:
          // when all the block's text is deleted but the line itself survives,
          // collapse the block to a zero-length anchor at the line start instead
          // of removing it, so the emptied line stays the block. The view drops it
          // if the line is actually gone (e.g. merged into another). Other blocks
          // are removed as before.
          if (ENRMBlockTypePersistsWhenEmpty(blockRange.type)) {
            blockRange.range = NSMakeRange(editLocation + insertedLength, 0);
          } else {
            [indexesToRemove addIndex:idx];
          }
          break;

        case EditOverlapDeletedInside: {
          NSUInteger newLength = blockRange.range.length - deletedLength + insertedLength;
          blockRange.range = NSMakeRange(rangeStart, newLength);
          break;
        }

        case EditOverlapClippedEnd: {
          NSUInteger newEnd = editLocation + insertedLength;
          NSUInteger newLength = newEnd > rangeStart ? newEnd - rangeStart : 0;
          blockRange.range = NSMakeRange(rangeStart, newLength);
          if (newLength == 0 && !ENRMBlockTypePersistsWhenEmpty(blockRange.type)) {
            [indexesToRemove addIndex:idx];
          }
          break;
        }

        case EditOverlapClippedStart: {
          NSUInteger charsClipped = deleteEnd - rangeStart;
          NSUInteger newStart = editLocation + insertedLength;
          NSUInteger newLength = blockRange.range.length - charsClipped;
          blockRange.range = NSMakeRange(newStart, newLength);
          if (newLength == 0 && !ENRMBlockTypePersistsWhenEmpty(blockRange.type)) {
            [indexesToRemove addIndex:idx];
          }
          break;
        }
      }
    } else {
      if (rangeStart == editLocation && blockRange.range.length == 0 &&
          ENRMBlockTypePersistsWhenEmpty(blockRange.type)) {
        // Typing into an empty (zero-length) persisting block: grow the anchor to
        // cover the inserted text rather than shifting past it, so the line
        // re-stamps as the block (heading/bullet).
        blockRange.range = NSMakeRange(rangeStart, insertedLength);
      } else if (rangeStart >= editLocation) {
        blockRange.range = NSMakeRange(rangeStart + insertedLength, blockRange.range.length);
      } else if (editLocation < rangeEnd) {
        blockRange.range = NSMakeRange(rangeStart, blockRange.range.length + insertedLength);
      }
    }
  }

  removeIndexesInReverse(_ranges, indexesToRemove);

  // Prune zero-length ranges, but keep zero-length persisting blocks: they anchor
  // an emptied-but-still-present heading/bullet line (see EditOverlapFullyDeleted).
  NSMutableIndexSet *emptyIndexes = [NSMutableIndexSet indexSet];
  for (NSUInteger idx = 0; idx < _ranges.count; idx++) {
    ENRMBlockRange *range = _ranges[idx];
    if (range.range.length == 0 && !ENRMBlockTypePersistsWhenEmpty(range.type)) {
      [emptyIndexes addIndex:idx];
    }
  }
  if (emptyIndexes.count > 0) {
    removeIndexesInReverse(_ranges, emptyIndexes);
  }
}

@end
