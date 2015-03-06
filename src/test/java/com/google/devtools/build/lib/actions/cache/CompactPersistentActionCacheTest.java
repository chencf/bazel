// Copyright 2015 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.actions.cache;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.devtools.build.lib.util.Clock;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.util.FsApparatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;

/**
 * Test for the CompactPersistentActionCache class.
 */
@RunWith(JUnit4.class)
public class CompactPersistentActionCacheTest {

  private static class ManualClock implements Clock {
    private long currentTime = 0L;

    ManualClock() { }

    @Override public long currentTimeMillis() {
      return currentTime;
    }

    @Override public long nanoTime() {
      return 0;
    }
  }

  private FsApparatus scratch = FsApparatus.newInMemory();
  private Path dataRoot;
  private Path mapFile;
  private Path journalFile;
  private ManualClock clock = new ManualClock();
  private CompactPersistentActionCache cache;

  @Before
  public void setUp() throws Exception {
    dataRoot = scratch.path("/cache/test.dat");
    cache = new CompactPersistentActionCache(dataRoot, clock);
    mapFile = CompactPersistentActionCache.cacheFile(dataRoot);
    journalFile = CompactPersistentActionCache.journalFile(dataRoot);
  }

  @Test
  public void testGetInvalidKey() {
    assertNull(cache.get("key"));
  }

  @Test
  public void testPutAndGet() {
    String key = "key";
    putKey(key);
    ActionCache.Entry readentry = cache.get(key);
    assertNotNull(readentry);
    assertEquals(cache.get(key).toString(), readentry.toString());
    assertFalse(mapFile.exists());
  }

  @Test
  public void testPutAndRemove() {
    String key = "key";
    putKey(key);
    cache.remove(key);
    assertNull(cache.get(key));
    assertFalse(mapFile.exists());
  }

  @Test
  public void testSave() throws IOException {
    String key = "key";
    putKey(key);
    cache.save();
    assertTrue(mapFile.exists());
    assertFalse(journalFile.exists());

    CompactPersistentActionCache newcache =
      new CompactPersistentActionCache(dataRoot, clock);
    ActionCache.Entry readentry = newcache.get(key);
    assertNotNull(readentry);
    assertEquals(cache.get(key).toString(), readentry.toString());
  }

  @Test
  public void testIncrementalSave() throws IOException {
    for (int i = 0; i < 300; i++) {
      putKey(Integer.toString(i));
    }
    assertFullSave();

    // Add 2 entries to 300. Might as well just leave them in the journal.
    putKey("abc");
    putKey("123");
    assertIncrementalSave(cache);

    // Make sure we have all the entries, including those in the journal,
    // after deserializing into a new cache.
    CompactPersistentActionCache newcache =
        new CompactPersistentActionCache(dataRoot, clock);
    for (int i = 0; i < 100; i++) {
      assertKeyEquals(cache, newcache, Integer.toString(i));
    }
    assertKeyEquals(cache, newcache, "abc");
    assertKeyEquals(cache, newcache, "123");
    putKey("xyz", newcache);
    assertIncrementalSave(newcache);

    // Make sure we can see previous journal values after a second incremental save.
    CompactPersistentActionCache newerCache =
        new CompactPersistentActionCache(dataRoot, clock);
    for (int i = 0; i < 100; i++) {
      assertKeyEquals(cache, newerCache, Integer.toString(i));
    }
    assertKeyEquals(cache, newerCache, "abc");
    assertKeyEquals(cache, newerCache, "123");
    assertNotNull(newerCache.get("xyz"));
    assertNull(newerCache.get("not_a_key"));

    // Add another 10 entries. This should not be incremental.
    for (int i = 300; i < 310; i++) {
      putKey(Integer.toString(i));
    }
    assertFullSave();
  }

  // Regression test to check that CompactActionCacheEntry.toString does not mutate the object.
  // Mutations may result in IllegalStateException.
  @Test
  public void testEntryToStringIsIdempotent() throws Exception {
    ActionCache.Entry entry = new ActionCache.Entry("actionKey");
    entry.toString();
    entry.addFile(new PathFragment("foo/bar"), Metadata.CONSTANT_METADATA);
    entry.toString();
    entry.getFileDigest();
    entry.toString();
  }

  private static void assertKeyEquals(ActionCache cache1, ActionCache cache2, String key) {
    Object entry = cache1.get(key);
    assertNotNull(entry);
    assertEquals(entry.toString(), cache2.get(key).toString());
  }

  private void assertFullSave() throws IOException {
    cache.save();
    assertTrue(mapFile.exists());
    assertFalse(journalFile.exists());
  }

  private void assertIncrementalSave(ActionCache ac) throws IOException {
    ac.save();
    assertTrue(mapFile.exists());
    assertTrue(journalFile.exists());
  }

  private void putKey(String key) {
    putKey(key, cache);
  }

  private void putKey(String key, ActionCache ac) {
    ActionCache.Entry entry = ac.createEntry(key);
    entry.getFileDigest();
    ac.put(key, entry);
  }
}
