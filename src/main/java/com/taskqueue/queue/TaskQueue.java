package com.taskqueue.queue;

import com.taskqueue.model.Task;

public interface TaskQueue {
    void enqueue(Task t);
    Task dequeue() throws InterruptedException;
    int size();
}
