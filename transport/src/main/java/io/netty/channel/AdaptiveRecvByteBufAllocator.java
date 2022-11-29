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
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    static final int DEFAULT_INITIAL = 1024;
    static final int DEFAULT_MAXIMUM = 65536;

    // 索引增量 4
    private static final int INDEX_INCREMENT = 4;
    // 索引减量 1
    private static final int INDEX_DECREMENT = 1;

    // size table
    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        // 向size数组  添加 ：16，32,48.... 496
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        // 继续向size数组 添加 ：512，1024,2048....一直到 int 值 溢出...成为负数
        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        // 数组赋值...
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        // 参数1：64在SIZE_TABLE的下标
        // 参数2：65536在SIZE_TABLE的下标
        // 参数3：1024
        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;
            // 计算出来 size 1024 在 SIZE_TABLE 的下标。
            index = getSizeTableIndex(initial);
            // nextReceiveBufferSize 表示下一次 分配出来的 byteBuf 容量大小。 默认第一次情况下，分配的byteBuf容量是 1024.
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // 条件成立：说明 读取的数据量 与 评估的数据量一致..说明ch内可能还有数据未读取完...还需要继续..
            if (bytes == attemptedBytesRead()) {
                // 这个方法想要更新 nextReceiveBufferSize 大小，因为 前面评估的 量  被读满了..可能意味着 ch 内 有很多数据,..咱们需要更大的 容器..
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }


        // 参数：真实读取的数据量，本次从ch内读取的数据量
        private void record(int actualReadBytes) {
            // 举个例子：
            // 假设 SIZE_TABLE[idx] = 512   =>    SIZE_TABLE[idx -1] = 496
            // 如果本次读取的数据量 <= 496，说明 ch的缓冲区 数据 不是很多 ,可能不需要 那么大的 ByteBuf
            // 如果第二次读取的数据量 <= 496，说明 ch的缓冲区 数据 不是很多 ,不需要 那么大的 ByteBuf
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {

                if (decreaseNow) {
                    // 初始阶段 定义过： 最小 不能 小于 TABLE_SIZE[minIndex]
                    index = max(index - INDEX_DECREMENT, minIndex);
                    // 获取相对减小的 BufferSize值 赋值给 nextReceiveBufferSize
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    // 第一次 设置成 true。
                    decreaseNow = true;
                }
            } // 条件成立：说明本次 ch 读请求，已经将 ByteBuf 容器装满了.. 说明ch内 可能还有很多很多的数据..所以，这里 让index 右移一位。
             // 获取出来一个 更大的 nextReceiveBufferSize。 下次会构建出来更大的 ByteBuf 对象。
            else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        // 参数1：64
        // 参数2：1024
        // 参数3：65536
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    // 参数1：64
    // 参数2：1024
    // 参数3：65536
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        // 使用二分查找算法  获取 mininum size 在 数组内的下标（ps：SIZE_TABLE[下标] <= minimum 值的）
        int minIndex = getSizeTableIndex(minimum);

        // 因为不能小于 minimum 值，所以 这里右移 index 。
        if (SIZE_TABLE[minIndex] < minimum) {
            // 确保 SIZE_TABLE[minIndex] >= minimum 值
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        // 使用二分查找算法  获取 maximum size 在 数组内的下标（ps：SIZE_TABLE[下标] >= maximum 值的）
        int maxIndex = getSizeTableIndex(maximum);
        // 因为不能超出 maximum 值，所以 这里左移 index 。
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        // 初始值 1024
        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        // 参数1：64在SIZE_TABLE的下标
        // 参数2：65536在SIZE_TABLE的下标
        // 参数3：1024
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
