/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Thread-safe list replacement for Create's {@code BeltInventory} lists.
 * Iterators walk a snapshot under synchronization and safely forward {@code remove()}
 * back to the backing list, preventing {@code ConcurrentModificationException} when a
 * region worker (e.g., hopper/funnel) inserts items onto a cross-region belt while
 * another thread ticks {@code BeltInventory}.
 */
public final class ConcurrentBeltList<E> extends AbstractList<E> {
    private final ArrayList<E> delegate = new ArrayList<>();
    private final Object lock = new Object();

    @Override
    public int size() {
        synchronized (this.lock) {
            return this.delegate.size();
        }
    }

    @Override
    public boolean isEmpty() {
        synchronized (this.lock) {
            return this.delegate.isEmpty();
        }
    }

    @Override
    public E get(int index) {
        synchronized (this.lock) {
            return this.delegate.get(index);
        }
    }

    @Override
    public E set(int index, E element) {
        synchronized (this.lock) {
            return this.delegate.set(index, element);
        }
    }

    @Override
    public boolean add(E e) {
        synchronized (this.lock) {
            return this.delegate.add(e);
        }
    }

    @Override
    public void add(int index, E element) {
        synchronized (this.lock) {
            this.delegate.add(index, element);
        }
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        synchronized (this.lock) {
            return this.delegate.addAll(c);
        }
    }

    @Override
    public E remove(int index) {
        synchronized (this.lock) {
            return this.delegate.remove(index);
        }
    }

    @Override
    public boolean remove(Object o) {
        synchronized (this.lock) {
            return this.delegate.remove(o);
        }
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        synchronized (this.lock) {
            return this.delegate.removeIf(filter);
        }
    }

    @Override
    public void clear() {
        synchronized (this.lock) {
            this.delegate.clear();
        }
    }

    @Override
    public boolean contains(Object o) {
        synchronized (this.lock) {
            return this.delegate.contains(o);
        }
    }

    @Override
    public void sort(Comparator<? super E> c) {
        synchronized (this.lock) {
            this.delegate.sort(c);
        }
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        final Object[] snapshot;
        synchronized (this.lock) {
            snapshot = this.delegate.toArray();
        }
        for (final Object o : snapshot) {
            @SuppressWarnings("unchecked")
            final E e = (E) o;
            action.accept(e);
        }
    }

    @Override
    public Iterator<E> iterator() {
        return this.listIterator(0);
    }

    @Override
    public ListIterator<E> listIterator() {
        return this.listIterator(0);
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        final Object[] snapshot;
        synchronized (this.lock) {
            snapshot = this.delegate.toArray();
        }
        if (index < 0 || index > snapshot.length) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return new SnapshotListIterator<>(snapshot, index, this.delegate, this.lock);
    }

    private static final class SnapshotListIterator<E> implements ListIterator<E> {
        private final Object[] snapshot;
        private int cursor;
        private int lastRet = -1;
        private final List<E> backing;
        private final Object lock;

        SnapshotListIterator(Object[] snapshot, int index, List<E> backing, Object lock) {
            this.snapshot = snapshot;
            this.cursor = index;
            this.backing = backing;
            this.lock = lock;
        }

        @Override
        public boolean hasNext() {
            return this.cursor < this.snapshot.length;
        }

        @Override
        @SuppressWarnings("unchecked")
        public E next() {
            if (this.cursor >= this.snapshot.length) {
                throw new NoSuchElementException();
            }
            this.lastRet = this.cursor++;
            return (E) this.snapshot[this.lastRet];
        }

        @Override
        public boolean hasPrevious() {
            return this.cursor > 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public E previous() {
            if (this.cursor <= 0) {
                throw new NoSuchElementException();
            }
            this.lastRet = --this.cursor;
            return (E) this.snapshot[this.lastRet];
        }

        @Override
        public int nextIndex() {
            return this.cursor;
        }

        @Override
        public int previousIndex() {
            return this.cursor - 1;
        }

        @Override
        public void remove() {
            if (this.lastRet < 0) {
                throw new IllegalStateException();
            }
            @SuppressWarnings("unchecked")
            final E target = (E) this.snapshot[this.lastRet];
            this.lastRet = -1;
            synchronized (this.lock) {
                this.backing.remove(target);
            }
        }

        @Override
        public void set(E e) {
            if (this.lastRet < 0) {
                throw new IllegalStateException();
            }
            @SuppressWarnings("unchecked")
            final E target = (E) this.snapshot[this.lastRet];
            this.snapshot[this.lastRet] = e;
            synchronized (this.lock) {
                final int idx = this.backing.indexOf(target);
                if (idx >= 0) {
                    this.backing.set(idx, e);
                }
            }
        }

        @Override
        public void add(E e) {
            this.lastRet = -1;
            synchronized (this.lock) {
                this.backing.add(e);
            }
        }
    }
}
