/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.buffer.PoolChunk.isSubpage;
import static java.lang.Math.max;

abstract class PoolArena<T> extends SizeClasses implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        Small,
        Normal
    }

    final PooledByteBufAllocator parent;

    final int numSmallSubpagePools;
    final int directMemoryCacheAlignment;
    final int directMemoryCacheAlignmentMask;
    private final PoolSubpage<T>[] smallSubpagePools;

    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;


    // 构造参数：
    // 参数一:this，当前allocator
    // 参数二：内存页大小，咱们定义的是 8192 b
    // 参数三：pageShifts => 13
    // 参数四：16mb => 16777216
    // 参数五：..
    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int pageShifts, int chunkSize, int cacheAlignment) {

        super(pageSize, pageShifts, chunkSize, cacheAlignment);
        this.parent = parent;
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;

        // nSubpages 表示 SizeClass中 有多少个 small 类型的class。
        // 32
        numSmallSubpagePools = nSubpages;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);

        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead();
        }

        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead() {
        PoolSubpage<T> head = new PoolSubpage<T>();
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        // 根据你请求的 内存大小，选择一个合适的 size类型。返回的是 这个 size类型 在 表格中的index
        final int sizeIdx = size2SizeIdx(reqCapacity);
        // 条件成立：说明请求的 内存大小，是 小于 small 区间内的 最大 size的，所以按照 small 类型 划分 一块内存。
        if (sizeIdx <= smallMaxSizeIdx) {
            // 参数一：线程cache
            // 参数二：buf，分配出来的内存 由 buf 装起来，提供给业务层使用。
            // 参数三：请求的内存大小
            // 参数四：类型index，在SizeClasses表格的索引。
            tcacheAllocateSmall(cache, buf, reqCapacity, sizeIdx);
        } else if (sizeIdx < nSizes) {
            tcacheAllocateNormal(cache, buf, reqCapacity, sizeIdx);
        } else {
            int normCapacity = directMemoryCacheAlignment > 0
                    ? normalizeSize(reqCapacity) : reqCapacity;
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, normCapacity);
        }
    }

    // 参数一：线程cache
    // 参数二：buf，分配出来的内存 由 buf 装起来，提供给业务层使用。
    // 参数三：请求的内存大小
    // 参数四：类型index，在SizeClasses表格的索引。
    private void tcacheAllocateSmall(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                     final int sizeIdx) {
        // 尝试从cache内 分配，看能不能成功，成功的话 就返回。
        // 参数一：分配给当前线程的 arena
        // 参数二：buf，分配出来的内存 由 buf 装起来，提供给业务层使用。
        // 参数三：请求的内存大小
        // 参数四：类型index，在SizeClasses表格的索引。
        if (cache.allocateSmall(this, buf, reqCapacity, sizeIdx)) {
            // was able to allocate out of the cache so move on
            return;
        }

        /**
         * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
         * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
         */
        // 获取到 指定 sizeClass 的 poolSubpage，这个是 PoolArena 的对象。
        // 拿到的这个 PoolSubpage 只是一个 head 对象，并没有任何 内存 在这上面管理。
        final PoolSubpage<T> head = smallSubpagePools[sizeIdx];
        final boolean needsNormalAllocation;
        synchronized (head) {
            // 初始化时，head.next = head.prev =head;
            final PoolSubpage<T> s = head.next;
            // needsNormalAllocation == true ，说明PoolArena 范围内 这个sizeIdx 的 subpage 也没初始化过..
            // 反之..已经PoolArena 范围内 这个sizeIdx 的subpage 有分配过。
            needsNormalAllocation = s == head;

            // 条件成立： 已经PoolArena 范围内 这个sizeIdx 的subpage 有分配过。
            if (!needsNormalAllocation) {
                assert s.doNotDestroy && s.elemSize == sizeIdx2size(sizeIdx);
                long handle = s.allocate();
                assert handle >= 0;
                s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity, cache);
            }
        }

        // 先看 没分配的逻辑。
        //
        if (needsNormalAllocation) {
            synchronized (this) {
                // 参数1：封装内存的ByteBuf
                // 参数2：业务层要拿的内存量
                // 参数3：sizeClass idx
                // 参数4：当前线程Cache缓存。
                allocateNormal(buf, reqCapacity, sizeIdx, cache);
            }
        }

        incSmallAllocation();
    }

    private void tcacheAllocateNormal(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                      final int sizeIdx) {
        if (cache.allocateNormal(this, buf, reqCapacity, sizeIdx)) {
            // was able to allocate out of the cache so move on
            return;
        }
        synchronized (this) {
            allocateNormal(buf, reqCapacity, sizeIdx, cache);
            ++allocationsNormal;
        }
    }

    // Method must be called inside synchronized(this) { ... } block
    // 参数1：封装内存的ByteBuf
    // 参数2：业务层要拿的内存量
    // 参数3：sizeClass idx
    // 参数4：当前线程Cache缓存。
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache threadCache) {
        // 尝试到 q050、q025... 这些PoolChunkList内去获取内存，成功则返回。
        if (q050.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q025.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q000.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            qInit.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q075.allocate(buf, reqCapacity, sizeIdx, threadCache)) {
            return;
        }

        // Add a new chunk.
        PoolChunk<T> c = newChunk(pageSize, nPSizes, pageShifts, chunkSize);
        boolean success = c.allocate(buf, reqCapacity, sizeIdx, threadCache);
        assert success;
        qInit.add(c);
    }

    private void incSmallAllocation() {
        allocationsSmall.increment();
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            SizeClass sizeClass = sizeClass(handle);
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }

            freeChunk(chunk, handle, normCapacity, sizeClass, nioBuffer, false);
        }
    }

    private SizeClass sizeClass(long handle) {
        return isSubpage(handle) ? SizeClass.Small : SizeClass.Normal;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, int normCapacity, SizeClass sizeClass, ByteBuffer nioBuffer,
                   boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    default:
                        throw new Error();
                }
            }
            destroyChunk = !chunk.parent.free(chunk, handle, normCapacity, nioBuffer);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    PoolSubpage<T> findSubpagePoolHead(int sizeIdx) {
        return smallSubpagePools[sizeIdx];
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        assert newCapacity >= 0 && newCapacity <= buf.maxCapacity();

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;

        // This does not touch buf's reader/writer indices
        allocate(parent.threadCache(), buf, newCapacity);
        int bytesToCopy;
        if (newCapacity > oldCapacity) {
            bytesToCopy = oldCapacity;
        } else {
            buf.trimIndicesToCapacity(newCapacity);
            bytesToCopy = newCapacity;
        }
        memoryCopy(oldMemory, oldOffset, buf, bytesToCopy);
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return 0;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return Collections.emptyList();
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return 0;
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public long numTinyDeallocations() {
        return 0;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return 0;
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, PooledByteBuf<T> dst, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        // 构造参数：
        // 参数一:this，当前allocator
        // 参数二：内存页大小，咱们定义的是 8192 b
        // 参数三：pageShifts => 13
        // 参数四：16mb => 16777216
        // 参数五：..
        HeapArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                  int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, pageShifts, chunkSize,
                  directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, pageShifts, chunkSize, maxPageIdx, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, PooledByteBuf<byte[]> dst, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst.memory, dst.offset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                    int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, pageShifts, chunkSize,
                  directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxPageIdx,
            int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                // 参数1：分配给当前线程的 poolArena
                // 参数2：allocateDirect(chunkSize)  分配一块16mb的直接内存，使用ByteBuffer引用。
                // 参数3：8k
                // 参数4：13  => 1 << x == 8192，所以x = 13
                // 参数5: 16mb
                // 参数6：maxPageIdx = parent.nPSizes，nPSizes 记录的是表格内 所有 sizeClass 大小 由 多个 “页”组成的 sizeClass个数。
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(chunkSize), pageSize, pageShifts,
                        chunkSize, maxPageIdx, 0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    pageShifts, chunkSize, maxPageIdx,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, PooledByteBuf<ByteBuffer> dstBuf, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dstBuf.memory) + dstBuf.offset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                ByteBuffer dst = dstBuf.internalNioBuffer();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstBuf.offset);
                dst.put(src);
            }
        }
    }
}
