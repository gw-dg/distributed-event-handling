package main.java.com.taskqueue.queue;
import main.java.com.taskqueue.model.Task;

interface TaskQueue {
    void enqueue(Task t);
    Task dequeue() throws InterruptedException;
    int size();
}