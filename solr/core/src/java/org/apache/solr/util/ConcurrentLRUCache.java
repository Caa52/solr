/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.util;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.PriorityQueue;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.solr.common.util.Cache;
import org.apache.solr.common.util.TimeSource;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.lang.ref.WeakReference;
import java.util.function.Function;

import static org.apache.lucene.util.RamUsageEstimator.HASHTABLE_RAM_BYTES_PER_ENTRY;
import static org.apache.lucene.util.RamUsageEstimator.QUERY_DEFAULT_RAM_BYTES_USED;

/**
 * A LRU cache implementation based upon ConcurrentHashMap and other techniques to reduce
 * contention and synchronization overhead to utilize multiple CPU cores more effectively.
 * <p>
 * Note that the implementation does not follow a true LRU (least-recently-used) eviction
 * strategy. Instead it strives to remove least recently used items but when the initial
 * cleanup does not remove enough items to reach the 'acceptableWaterMark' limit, it can
 * remove more items forcefully regardless of access order.
 *
 *
 * @since solr 1.4
 */
public class ConcurrentLRUCache<K,V> implements Cache<K,V>, Accountable {

  private static final long BASE_RAM_BYTES_USED =
      RamUsageEstimator.shallowSizeOfInstance(ConcurrentLRUCache.class) +
      new Stats().ramBytesUsed() +
      RamUsageEstimator.primitiveSizes.get(long.class) +
      RamUsageEstimator.shallowSizeOfInstance(ConcurrentHashMap.class);

  private final ConcurrentHashMap<Object, CacheEntry<K,V>> map;
  private int upperWaterMark, lowerWaterMark;
  private final ReentrantLock markAndSweepLock = new ReentrantLock(true);
  private boolean isCleaning = false;  // not volatile... piggybacked on other volatile vars
  private boolean newThreadForCleanup;
  private volatile boolean islive = true;
  private final Stats stats = new Stats();
  private int acceptableWaterMark;
  private long oldestEntry = 0;  // not volatile, only accessed in the cleaning method
  private final TimeSource timeSource = TimeSource.NANO_TIME;
  private final AtomicLong oldestEntryNs = new AtomicLong(0);
  private long maxIdleTimeNs;
  private final EvictionListener<K,V> evictionListener;
  private CleanupThread cleanupThread;
  private boolean runCleanupThread;

  private long ramLowerWatermark, ramUpperWatermark;
  private final LongAdder ramBytes = new LongAdder();

  public ConcurrentLRUCache(long ramLowerWatermark, long ramUpperWatermark,
                            boolean runCleanupThread, EvictionListener<K, V> evictionListener) {
    this(ramLowerWatermark, ramUpperWatermark, runCleanupThread, evictionListener, -1);
  }

  public ConcurrentLRUCache(long ramLowerWatermark, long ramUpperWatermark,
                            boolean runCleanupThread, EvictionListener<K, V> evictionListener,
                            int maxIdleTimeSec) {
    this.ramLowerWatermark = ramLowerWatermark;
    this.ramUpperWatermark = ramUpperWatermark;

    this.evictionListener = evictionListener;
    this.map = new ConcurrentHashMap<>();
    this.newThreadForCleanup = false;

    this.acceptableWaterMark = -1;
    this.lowerWaterMark = Integer.MIN_VALUE;
    this.upperWaterMark = Integer.MAX_VALUE;

    setMaxIdleTime(maxIdleTimeSec);
    setRunCleanupThread(runCleanupThread);
  }

  public ConcurrentLRUCache(int upperWaterMark, final int lowerWaterMark, int acceptableWatermark,
                            int initialSize, boolean runCleanupThread, boolean runNewThreadForCleanup,
                            EvictionListener<K,V> evictionListener) {
    this(upperWaterMark, lowerWaterMark, acceptableWatermark, initialSize, runCleanupThread,
        runNewThreadForCleanup, evictionListener, -1);
  }

  public ConcurrentLRUCache(int upperWaterMark, final int lowerWaterMark, int acceptableWatermark,
                            int initialSize, boolean runCleanupThread, boolean runNewThreadForCleanup,
                            EvictionListener<K,V> evictionListener, int maxIdleTimeSec) {
    if (upperWaterMark < 1) throw new IllegalArgumentException("upperWaterMark must be > 0");
    if (lowerWaterMark >= upperWaterMark)
      throw new IllegalArgumentException("lowerWaterMark must be  < upperWaterMark");
    map = new ConcurrentHashMap<>(initialSize);
    newThreadForCleanup = runNewThreadForCleanup;
    this.upperWaterMark = upperWaterMark;
    this.lowerWaterMark = lowerWaterMark;
    this.acceptableWaterMark = acceptableWatermark;
    this.evictionListener = evictionListener;
    this.ramLowerWatermark = Long.MIN_VALUE;
    this.ramUpperWatermark = Long.MAX_VALUE;
    setMaxIdleTime(maxIdleTimeSec);
    setRunCleanupThread(runCleanupThread);
  }

  public ConcurrentLRUCache(int size, int lowerWatermark) {
    this(size, lowerWatermark, (int) Math.floor((lowerWatermark + size) / 2),
            (int) Math.ceil(0.75 * size), false, false, null, -1);
  }

  public void setAlive(boolean live) {
    islive = live;
  }

  public void setUpperWaterMark(int upperWaterMark) {
    if (upperWaterMark < 1) throw new IllegalArgumentException("upperWaterMark must be >= 1");
    this.upperWaterMark = upperWaterMark;
  }

  public void setLowerWaterMark(int lowerWaterMark) {
    this.lowerWaterMark = lowerWaterMark;
  }

  public void setAcceptableWaterMark(int acceptableWaterMark) {
    this.acceptableWaterMark = acceptableWaterMark;
  }

  public void setRamUpperWatermark(long ramUpperWatermark) {
    if (ramUpperWatermark < 1) {
      throw new IllegalArgumentException("ramUpperWaterMark must be >= 1");
    }
    this.ramUpperWatermark = ramUpperWatermark;
  }

  public void setRamLowerWatermark(long ramLowerWatermark) {
    if (ramLowerWatermark < 0) {
      throw new IllegalArgumentException("ramLowerWaterMark must be >= 0");
    }
    this.ramLowerWatermark = ramLowerWatermark;
  }

  public void setMaxIdleTime(int maxIdleTime) {
    long oldMaxIdleTimeNs = maxIdleTimeNs;
    maxIdleTimeNs = maxIdleTime > 0 ? TimeUnit.NANOSECONDS.convert(maxIdleTime, TimeUnit.SECONDS) : Long.MAX_VALUE;
    if (cleanupThread != null && maxIdleTimeNs < oldMaxIdleTimeNs) {
      cleanupThread.wakeThread();
    }
  }

  public synchronized void setRunCleanupThread(boolean runCleanupThread) {
    this.runCleanupThread = runCleanupThread;
    if (this.runCleanupThread) {
      if (cleanupThread == null) {
        cleanupThread = new CleanupThread(this);
        cleanupThread.start();
      }
    } else {
      if (cleanupThread != null) {
        cleanupThread.stopThread();
        cleanupThread = null;
      }
    }
  }

  @Override
  public V get(K key) {
    CacheEntry<K, V> e = map.get(key);
    if (e == null) {
      if (islive) stats.missCounter.increment();
      return null;
    }
    if (islive) e.lastAccessed = stats.accessCounter.incrementAndGet();
    return e.value;
  }

  @Override
  public V remove(K key) {
    CacheEntry<K, V> cacheEntry = map.remove(key);
    if (cacheEntry != null) {
      stats.size.decrement();
      ramBytes.add(-cacheEntry.ramBytesUsed() - HASHTABLE_RAM_BYTES_PER_ENTRY);
      return cacheEntry.value;
    }
    return null;
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    // prescreen access first
    V val = get(key);
    if (val != null) {
      return val;
    }
    AtomicBoolean newEntry = new AtomicBoolean();
    CacheEntry<K, V> entry = map.computeIfAbsent(key, k -> {
      V value = mappingFunction.apply(key);
      // preserve the semantics of computeIfAbsent
      if (value == null) {
        return null;
      }
      CacheEntry<K, V> e = new CacheEntry<>(key, value, timeSource.getEpochTimeNs(), stats.accessCounter.incrementAndGet());
      oldestEntryNs.updateAndGet(x -> x > e.createTime  || x == 0 ? e.createTime : x);
      stats.size.increment();
      ramBytes.add(e.ramBytesUsed() + HASHTABLE_RAM_BYTES_PER_ENTRY); // added key + value + entry
      if (islive) {
        stats.putCounter.increment();
      } else {
        stats.nonLivePutCounter.increment();
      }
      newEntry.set(true);
      return e;
    });
    if (newEntry.get()) {
      maybeMarkAndSweep();
    } else {
      if (islive && entry != null) {
        entry.lastAccessed = stats.accessCounter.incrementAndGet();
      }
    }
    return entry != null ? entry.value : null;
  }

  @Override
  public V put(K key, V val) {
    if (val == null) return null;
    CacheEntry<K, V> e = new CacheEntry<>(key, val, timeSource.getEpochTimeNs(), stats.accessCounter.incrementAndGet());
    return putCacheEntry(e);
  }

  /**
   * Visible for testing to create synthetic cache entries.
   * @lucene.internal
   */
  public V putCacheEntry(CacheEntry<K, V> e) {
    // initialize oldestEntryNs
    oldestEntryNs.updateAndGet(x -> x > e.createTime  || x == 0 ? e.createTime : x);
    CacheEntry<K,V> oldCacheEntry = map.put(e.key, e);
    if (oldCacheEntry == null) {
      stats.size.increment();
      ramBytes.add(e.ramBytesUsed() + HASHTABLE_RAM_BYTES_PER_ENTRY); // added key + value + entry
    } else {
      ramBytes.add(-oldCacheEntry.ramBytesUsed());
      ramBytes.add(e.ramBytesUsed());
    }
    if (islive) {
      stats.putCounter.increment();
    } else {
      stats.nonLivePutCounter.increment();
    }
    maybeMarkAndSweep();
    return oldCacheEntry == null ? null : oldCacheEntry.value;
  }

  private void maybeMarkAndSweep() {
    // Check if we need to clear out old entries from the cache.
    // isCleaning variable is checked instead of markAndSweepLock.isLocked()
    // for performance because every put invocation will check until
    // the size is back to an acceptable level.
    //
    // There is a race between the check and the call to markAndSweep, but
    // it's unimportant because markAndSweep actually acquires the lock or returns if it can't.
    //
    // Thread safety note: isCleaning read is piggybacked (comes after) other volatile reads
    // in this method.
    long idleCutoff = timeSource.getEpochTimeNs() - maxIdleTimeNs;
    int currentSize = stats.size.intValue();
    if ((currentSize > upperWaterMark || ramBytes.sum() > ramUpperWatermark || oldestEntryNs.get() < idleCutoff) && !isCleaning) {
      if (newThreadForCleanup) {
        new Thread(this::markAndSweep, "CacheCleanupThread").start();
      } else if (cleanupThread != null){
        cleanupThread.wakeThread();
      } else {
        markAndSweep();
      }
    }
  }

  /**
   * Removes items from the cache to bring the size down
   * to an acceptable value.
   * <p>Visible for unit testing.</p>
   * @lucene.internal
   */
   public void markAndSweep() {
    // if we want to keep at least 1000 entries, then timestamps of
    // current through current-1000 are guaranteed not to be the oldest (but that does
    // not mean there are 1000 entries in that group... it's actually anywhere between
    // 1 and 1000).
    // Also, if we want to remove 500 entries, then
    // oldestEntry through oldestEntry+500 are guaranteed to be
    // removed (however many there are there).

    if (!markAndSweepLock.tryLock()) return;
    try {
      if (maxIdleTimeNs != Long.MAX_VALUE) {
        long idleCutoff = timeSource.getEpochTimeNs() - maxIdleTimeNs;
        if (oldestEntryNs.get() < idleCutoff) {
          markAndSweepByIdleTime();
        }
      }
      if (upperWaterMark < size()) {
        markAndSweepByCacheSize();
      } else if (ramUpperWatermark < ramBytesUsed()) {
        markAndSweepByRamSize();
      } else if (upperWaterMark == Integer.MAX_VALUE && ramUpperWatermark == Long.MAX_VALUE) {
        // should never happen
        throw new AssertionError("ConcurrentLRUCache initialized with neither size limits nor ram limits");
      }
    } finally {
      isCleaning = false;  // set before markAndSweep.unlock() for visibility
      markAndSweepLock.unlock();
    }
  }

  /*
    Must be called after acquiring markAndSweepLock
   */
  private void markAndSweepByIdleTime() {
    assert markAndSweepLock.isHeldByCurrentThread() : "markAndSweepLock held by another thread";
    Iterator<Map.Entry<Object, CacheEntry<K, V>>> iterator = map.entrySet().iterator();
    long idleCutoff = timeSource.getEpochTimeNs() - maxIdleTimeNs;
    long currentOldestEntry = Long.MAX_VALUE;
    while (iterator.hasNext()) {
      Map.Entry<Object, CacheEntry<K, V>> entry = iterator.next();
      if (entry.getValue().createTime < idleCutoff) {
        iterator.remove();
        stats.evictionIdleCounter.increment();
        postRemoveEntry(entry.getValue());
      } else {
        if (entry.getValue().createTime < currentOldestEntry) {
          currentOldestEntry = entry.getValue().createTime;
        }
      }
    }
    if (currentOldestEntry != Long.MAX_VALUE) {
      oldestEntryNs.set(currentOldestEntry);
    }
  }

  /*
    Must be called after acquiring markAndSweepLock
   */
  private void markAndSweepByRamSize() {
    assert markAndSweepLock.isHeldByCurrentThread() : "markAndSweepLock held by another thread";
    List<CacheEntry<K, V>> entriesInAccessOrder = new ArrayList<>(map.size());
    map.forEach((o, kvCacheEntry) -> {
      kvCacheEntry.lastAccessedCopy = kvCacheEntry.lastAccessed; // important because we want to avoid volatile read during comparisons
      entriesInAccessOrder.add(kvCacheEntry);
    });

    Collections.sort(entriesInAccessOrder); // newer access is smaller, older access is bigger

    // iterate in oldest to newest order
    for (int i = entriesInAccessOrder.size() - 1; i >= 0; i--) {
      CacheEntry<K, V> kvCacheEntry = entriesInAccessOrder.get(i);
      evictEntry(kvCacheEntry.key);
      if (ramBytes.sum() <= ramLowerWatermark)  {
        break; // we are done!
      }
    }
  }

  /*
   * Removes items from the cache to bring the size down
   * to an acceptable value ('acceptableWaterMark').
   * <p>
   * It is done in two stages. In the first stage, least recently used items are evicted.
   * If, after the first stage, the cache size is still greater than 'acceptableSize'
   * config parameter, the second stage takes over.
   * </p>
   * <p>The second stage is more intensive and tries to bring down the cache size
   * to the 'lowerWaterMark' config parameter.</p>
   * Must be called after acquiring markAndSweepLock
   */
  private void markAndSweepByCacheSize() {
    assert markAndSweepLock.isHeldByCurrentThread() : "markAndSweepLock held by another thread";
    long oldestEntry = this.oldestEntry;
    isCleaning = true;
    this.oldestEntry = oldestEntry;     // volatile write to make isCleaning visible

    long timeCurrent = stats.accessCounter.longValue();
    int sz = stats.size.intValue();

    int numRemoved = 0;
    int numKept = 0;
    long newestEntry = timeCurrent;
    long newNewestEntry = -1;
    long newOldestEntry = Long.MAX_VALUE;

    int wantToKeep = lowerWaterMark;
    int wantToRemove = sz - lowerWaterMark;

    @SuppressWarnings("unchecked")
    CacheEntry<K,V>[] eset = (CacheEntry<K, V>[]) Array.newInstance(CacheEntry.class, sz);
    int eSize = 0;

    // System.out.println("newestEntry="+newestEntry + " oldestEntry="+oldestEntry);
    // System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));

    for (CacheEntry<K,V> ce : map.values()) {
      // set lastAccessedCopy to avoid more volatile reads
      ce.lastAccessedCopy = ce.lastAccessed;
      long thisEntry = ce.lastAccessedCopy;

      // since the wantToKeep group is likely to be bigger than wantToRemove, check it first
      if (thisEntry > newestEntry - wantToKeep) {
        // this entry is guaranteed not to be in the bottom
        // group, so do nothing.
        numKept++;
        newOldestEntry = Math.min(thisEntry, newOldestEntry);
      } else if (thisEntry < oldestEntry + wantToRemove) { // entry in bottom group?
        // this entry is guaranteed to be in the bottom group
        // so immediately remove it from the map.
        evictEntry(ce.key);
        numRemoved++;
      } else {
        // This entry *could* be in the bottom group.
        // Collect these entries to avoid another full pass... this is wasted
        // effort if enough entries are normally removed in this first pass.
        // An alternate impl could make a full second pass.
        if (eSize < eset.length-1) {
          eset[eSize++] = ce;
          newNewestEntry = Math.max(thisEntry, newNewestEntry);
          newOldestEntry = Math.min(thisEntry, newOldestEntry);
        }
      }
    }

    // System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));
    // TODO: allow this to be customized in the constructor?
    int numPasses=1; // maximum number of linear passes over the data

    // if we didn't remove enough entries, then make more passes
    // over the values we collected, with updated min and max values.
    while (sz - numRemoved > acceptableWaterMark && --numPasses>=0) {

      oldestEntry = newOldestEntry == Long.MAX_VALUE ? oldestEntry : newOldestEntry;
      newOldestEntry = Long.MAX_VALUE;
      newestEntry = newNewestEntry;
      newNewestEntry = -1;
      wantToKeep = lowerWaterMark - numKept;
      wantToRemove = sz - lowerWaterMark - numRemoved;

      // iterate backward to make it easy to remove items.
      for (int i=eSize-1; i>=0; i--) {
        CacheEntry<K,V> ce = eset[i];
        long thisEntry = ce.lastAccessedCopy;

        if (thisEntry > newestEntry - wantToKeep) {
          // this entry is guaranteed not to be in the bottom
          // group, so do nothing but remove it from the eset.
          numKept++;
          // remove the entry by moving the last element to its position
          eset[i] = eset[eSize-1];
          eSize--;

          newOldestEntry = Math.min(thisEntry, newOldestEntry);

        } else if (thisEntry < oldestEntry + wantToRemove) { // entry in bottom group?

          // this entry is guaranteed to be in the bottom group
          // so immediately remove it from the map.
          evictEntry(ce.key);
          numRemoved++;

          // remove the entry by moving the last element to its position
          eset[i] = eset[eSize-1];
          eSize--;
        } else {
          // This entry *could* be in the bottom group, so keep it in the eset,
          // and update the stats.
          newNewestEntry = Math.max(thisEntry, newNewestEntry);
          newOldestEntry = Math.min(thisEntry, newOldestEntry);
        }
      }
      // System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));
    }


    // if we still didn't remove enough entries, then make another pass while
    // inserting into a priority queue
    if (sz - numRemoved > acceptableWaterMark) {

      oldestEntry = newOldestEntry == Long.MAX_VALUE ? oldestEntry : newOldestEntry;
      newOldestEntry = Long.MAX_VALUE;
      newestEntry = newNewestEntry;
      newNewestEntry = -1;
      wantToKeep = lowerWaterMark - numKept;
      wantToRemove = sz - lowerWaterMark - numRemoved;

      PQueue<K,V> queue = new PQueue<>(wantToRemove);

      for (int i=eSize-1; i>=0; i--) {
        CacheEntry<K,V> ce = eset[i];
        long thisEntry = ce.lastAccessedCopy;

        if (thisEntry > newestEntry - wantToKeep) {
          // this entry is guaranteed not to be in the bottom
          // group, so do nothing but remove it from the eset.
          numKept++;
          // removal not necessary on last pass.
          // eset[i] = eset[eSize-1];
          // eSize--;

          newOldestEntry = Math.min(thisEntry, newOldestEntry);

        } else if (thisEntry < oldestEntry + wantToRemove) {  // entry in bottom group?
          // this entry is guaranteed to be in the bottom group
          // so immediately remove it.
          evictEntry(ce.key);
          numRemoved++;

          // removal not necessary on last pass.
          // eset[i] = eset[eSize-1];
          // eSize--;
        } else {
          // This entry *could* be in the bottom group.
          // add it to the priority queue

          // everything in the priority queue will be removed, so keep track of
          // the lowest value that ever comes back out of the queue.

          // first reduce the size of the priority queue to account for
          // the number of items we have already removed while executing
          // this loop so far.
          queue.myMaxSize = sz - lowerWaterMark - numRemoved;
          while (queue.size() > queue.myMaxSize && queue.size() > 0) {
            CacheEntry<K, V> otherEntry = queue.pop();
            newOldestEntry = Math.min(otherEntry.lastAccessedCopy, newOldestEntry);
          }
          if (queue.myMaxSize <= 0) break;

          CacheEntry<K, V> o = queue.myInsertWithOverflow(ce);
          if (o != null) {
            newOldestEntry = Math.min(o.lastAccessedCopy, newOldestEntry);
          }
        }
      }

      // Now delete everything in the priority queue.
      // avoid using pop() since order doesn't matter anymore
      for (CacheEntry<K,V> ce : queue.getValues()) {
        if (ce==null) continue;
        evictEntry(ce.key);
        numRemoved++;
      }

      // System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " initialQueueSize="+ wantToRemove + " finalQueueSize=" + queue.size() + " sz-numRemoved=" + (sz-numRemoved));
    }

    oldestEntry = newOldestEntry == Long.MAX_VALUE ? oldestEntry : newOldestEntry;
    this.oldestEntry = oldestEntry;
  }

  private static class PQueue<K,V> extends PriorityQueue<CacheEntry<K,V>> {
    int myMaxSize;
    final Object[] heap;
    
    PQueue(int maxSz) {
      super(maxSz);
      heap = getHeapArray();
      myMaxSize = maxSz;
    }

    @SuppressWarnings("unchecked")
    Iterable<CacheEntry<K,V>> getValues() { 
      return (Iterable) Collections.unmodifiableCollection(Arrays.asList(heap));
    }

    @Override
    protected boolean lessThan(CacheEntry<K,V> a,
                               CacheEntry<K,V> b) {
      // reverse the parameter order so that the queue keeps the oldest items
      return b.lastAccessedCopy < a.lastAccessedCopy;
    }

    // necessary because maxSize is private in base class
    @SuppressWarnings("unchecked")
    public CacheEntry<K,V> myInsertWithOverflow(CacheEntry<K,V> element) {
      if (size() < myMaxSize) {
        add(element);
        return null;
      } else if (size() > 0 && !lessThan(element, (CacheEntry<K,V>) heap[1])) {
        CacheEntry<K,V> ret = (CacheEntry<K,V>) heap[1];
        heap[1] = element;
        updateTop();
        return ret;
      } else {
        return element;
      }
    }
  }


  private void evictEntry(K key) {
    CacheEntry<K,V> o = map.remove(key);
    postRemoveEntry(o);
  }

  private void postRemoveEntry(CacheEntry<K, V> o) {
    if (o == null) return;
    ramBytes.add(-(o.ramBytesUsed() + HASHTABLE_RAM_BYTES_PER_ENTRY));
    stats.size.decrement();
    stats.evictionCounter.increment();
    if(evictionListener != null) evictionListener.evictedEntry(o.key,o.value);
  }

  /**
   * Returns 'n' number of oldest accessed entries present in this cache.
   *
   * This uses a TreeSet to collect the 'n' oldest items ordered by ascending last access time
   *  and returns a LinkedHashMap containing 'n' or less than 'n' entries.
   * @param n the number of oldest items needed
   * @return a LinkedHashMap containing 'n' or less than 'n' entries
   */
  public Map<K, V> getOldestAccessedItems(int n) {
    Map<K, V> result = new LinkedHashMap<>();
    if (n <= 0)
      return result;
    TreeSet<CacheEntry<K,V>> tree = new TreeSet<>();
    markAndSweepLock.lock();
    try {
      for (Map.Entry<Object, CacheEntry<K,V>> entry : map.entrySet()) {
        CacheEntry<K,V> ce = entry.getValue();
        ce.lastAccessedCopy = ce.lastAccessed;
        if (tree.size() < n) {
          tree.add(ce);
        } else {
          if (ce.lastAccessedCopy < tree.first().lastAccessedCopy) {
            tree.remove(tree.first());
            tree.add(ce);
          }
        }
      }
    } finally {
      markAndSweepLock.unlock();
    }
    for (CacheEntry<K,V> e : tree) {
      result.put(e.key, e.value);
    }
    return result;
  }

  public Map<K,V> getLatestAccessedItems(int n) {
    Map<K,V> result = new LinkedHashMap<>();
    if (n <= 0)
      return result;
    TreeSet<CacheEntry<K,V>> tree = new TreeSet<>();
    // we need to grab the lock since we are changing lastAccessedCopy
    markAndSweepLock.lock();
    try {
      for (Map.Entry<Object, CacheEntry<K,V>> entry : map.entrySet()) {
        CacheEntry<K,V> ce = entry.getValue();
        ce.lastAccessedCopy = ce.lastAccessed;
        if (tree.size() < n) {
          tree.add(ce);
        } else {
          if (ce.lastAccessedCopy > tree.last().lastAccessedCopy) {
            tree.remove(tree.last());
            tree.add(ce);
          }
        }
      }
    } finally {
      markAndSweepLock.unlock();
    }
    for (CacheEntry<K,V> e : tree) {
      result.put(e.key, e.value);
    }
    return result;
  }

  public int size() {
    return stats.size.intValue();
  }

  @Override
  public void clear() {
    map.clear();
    ramBytes.reset();
  }

  public Map<Object, CacheEntry<K,V>> getMap() {
    return map;
  }

  public static class CacheEntry<K,V> implements Comparable<CacheEntry<K,V>>, Accountable {
    public static long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOf(CacheEntry.class);

    final K key;
    final V value;
    final long createTime;
    final long ramBytesUsed; // cache
    volatile long lastAccessed = 0;
    long lastAccessedCopy = 0;


    public CacheEntry(K key, V value, long createTime, long lastAccessed) {
      this.key = key;
      this.value = value;
      this.createTime = createTime;
      this.lastAccessed = lastAccessed;
      this.ramBytesUsed =
          BASE_RAM_BYTES_USED +
          RamUsageEstimator.sizeOfObject(key, QUERY_DEFAULT_RAM_BYTES_USED) +
          RamUsageEstimator.sizeOfObject(value, QUERY_DEFAULT_RAM_BYTES_USED);
    }

    public void setLastAccessed(long lastAccessed) {
      this.lastAccessed = lastAccessed;
    }

    @Override
    public int compareTo(CacheEntry<K,V> that) {
      if (this.lastAccessedCopy == that.lastAccessedCopy) return 0;
      return this.lastAccessedCopy < that.lastAccessedCopy ? 1 : -1;
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return value.equals(obj);
    }

    @Override
    public String toString() {
      return "key: " + key + " value: " + value + " lastAccessed:" + lastAccessed;
    }

    @Override
    public long ramBytesUsed() {
      return ramBytesUsed;
    }

    @Override
    public Collection<Accountable> getChildResources() {
      return Collections.emptyList();
    }
  }

 private boolean isDestroyed =  false;
  public void destroy() {
    try {
      if(cleanupThread != null){
        cleanupThread.stopThread();
      }
    } finally {
      isDestroyed = true;
    }
  }

  public Stats getStats() {
    return stats;
  }


  public static class Stats implements Accountable {
    private static final long RAM_BYTES_USED =
        // accounts for field refs
        RamUsageEstimator.shallowSizeOfInstance(Stats.class) +
            // LongAdder
            6 * (
                RamUsageEstimator.NUM_BYTES_ARRAY_HEADER +
                RamUsageEstimator.primitiveSizes.get(long.class) +
                2 * (RamUsageEstimator.NUM_BYTES_OBJECT_REF + RamUsageEstimator.primitiveSizes.get(long.class))
            ) +
            // AtomicLong
            RamUsageEstimator.primitiveSizes.get(long.class);

    private final AtomicLong accessCounter = new AtomicLong(0);
    private final LongAdder putCounter = new LongAdder();
    private final LongAdder nonLivePutCounter = new LongAdder();
    private final LongAdder missCounter = new LongAdder();
    private final LongAdder size = new LongAdder();
    private LongAdder evictionCounter = new LongAdder();
    private LongAdder evictionIdleCounter = new LongAdder();

    public long getCumulativeLookups() {
      return (accessCounter.longValue() - putCounter.longValue() - nonLivePutCounter.longValue()) + missCounter.longValue();
    }

    public long getCumulativeHits() {
      return accessCounter.longValue() - putCounter.longValue() - nonLivePutCounter.longValue();
    }

    public long getCumulativePuts() {
      return putCounter.longValue();
    }

    public long getCumulativeEvictions() {
      return evictionCounter.longValue();
    }

    public long getCumulativeIdleEvictions() {
      return evictionIdleCounter.longValue();
    }

    public int getCurrentSize() {
      return size.intValue();
    }

    public long getCumulativeNonLivePuts() {
      return nonLivePutCounter.longValue();
    }

    public long getCumulativeMisses() {
      return missCounter.longValue();
    }

    public void add(Stats other) {
      accessCounter.addAndGet(other.accessCounter.get());
      putCounter.add(other.putCounter.longValue());
      nonLivePutCounter.add(other.nonLivePutCounter.longValue());
      missCounter.add(other.missCounter.longValue());
      evictionCounter.add(other.evictionCounter.longValue());
      long maxSize = Math.max(size.longValue(), other.size.longValue());
      size.reset();
      size.add(maxSize);
    }

    @Override
    public long ramBytesUsed() {
      return RAM_BYTES_USED;
    }
  }

  public static interface EvictionListener<K,V>{
    public void evictedEntry(K key, V value);
  }

  private static class CleanupThread extends Thread {
    private final WeakReference<ConcurrentLRUCache<?,?>> cache;

    private boolean stop = false;

    public CleanupThread(ConcurrentLRUCache<?,?> c) {
      super("CacheCleanupThread");
      cache = new WeakReference<>(c);
    }

    @Override
    public void run() {
      while (true) {
        ConcurrentLRUCache<?,?> c = cache.get();
        if(c == null) break;
        synchronized (this) {
          if (stop) break;
          long waitTimeMs =  c.maxIdleTimeNs != Long.MAX_VALUE ? TimeUnit.MILLISECONDS.convert(c.maxIdleTimeNs, TimeUnit.NANOSECONDS) : 0L;
          try {
            this.wait(waitTimeMs);
          } catch (InterruptedException e) {}
        }
        if (stop) break;
        c = cache.get();
        if (c == null) break;
        c.markAndSweep();
      }
    }

    void wakeThread() {
      synchronized(this){
        this.notify();
      }
    }

    void stopThread() {
      synchronized(this){
        stop=true;
        this.notify();
      }
    }
  }

  @Override
  public long ramBytesUsed() {
    return BASE_RAM_BYTES_USED + ramBytes.sum();
  }

  @Override
  public Collection<Accountable> getChildResources() {
    return Collections.emptyList();
  }
}
