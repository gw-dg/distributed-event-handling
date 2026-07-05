package main.java.com.taskqueue.queue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import main.java.com.taskqueue.model.Task;

final class InMemoryTaskQueue implements TaskQueue {

    private static final int DEFAULT_CAPACITY = 100;
    private final BlockingQueue<Task> queue;

    public InMemoryTaskQueue() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryTaskQueue(int capacity) {
        if (capacity <= 0) {
            throw IllegalArgumentException("Capacity can't be less then 1");
        }

        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void enqueue(Task t) {
        Object.requireNotNull(t, "task can't be null");
        if (t.isTerminal()) {
            throw IllegalStateException("can't insert into a terminal task " + t.id());
        }

        try {
            queue.put(t);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while enqueueing task", e);
        }
    }

    @Override
    public Task dequeue() throws InterruptedException {
        return queue.take();
    }

    @Override
    public int size() {
        return queue.size();
    }
}
