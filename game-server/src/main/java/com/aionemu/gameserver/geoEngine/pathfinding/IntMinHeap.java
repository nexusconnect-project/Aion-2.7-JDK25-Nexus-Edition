/*
 * This file is part of aion-lightning <aion-lightning.com>.
 *
 *  aion-lightning is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  aion-lightning is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with aion-lightning.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.geoEngine.pathfinding;

/**
 * Array-backed binary min-heap used by the JPS open list. Each entry is a packed long:
 * {@code (f << 32) | nodeIndex}. No object allocations during a search.
 *
 * @author aion-lightning
 */
final class IntMinHeap {

	private long[] heap;
	private int size;

	IntMinHeap() {
		this(256);
	}

	IntMinHeap(int capacity) {
		heap = new long[Math.max(16, capacity)];
	}

	boolean isEmpty() {
		return size == 0;
	}

	int size() {
		return size;
	}

	void clear() {
		size = 0;
	}

	void add(long entry) {
		if (size == heap.length) {
			long[] grown = new long[heap.length * 2];
			System.arraycopy(heap, 0, grown, 0, heap.length);
			heap = grown;
		}
		heap[size] = entry;
		siftUp(size);
		size++;
	}

	long pop() {
		long top = heap[0];
		size--;
		if (size > 0) {
			heap[0] = heap[size];
			siftDown(0);
		}
		return top;
	}

	long peek() {
		return heap[0];
	}

	private void siftUp(int index) {
		long value = heap[index];
		while (index > 0) {
			int parent = (index - 1) >>> 1;
			if (heap[parent] <= value) {
				break;
			}
			heap[index] = heap[parent];
			index = parent;
		}
		heap[index] = value;
	}

	private void siftDown(int index) {
		long value = heap[index];
		int half = size >>> 1;
		while (index < half) {
			int child = (index << 1) + 1;
			long childValue = heap[child];
			int right = child + 1;
			if (right < size && heap[right] < childValue) {
				child = right;
				childValue = heap[right];
			}
			if (childValue >= value) {
				break;
			}
			heap[index] = childValue;
			index = child;
		}
		heap[index] = value;
	}
}
