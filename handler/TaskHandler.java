package main.java.com.taskqueue.handler;

import main.java.com.taskqueue.common.Result;
import main.java.com.taskqueue.model.Task;

@FunctionalInterface
public interface TaskHandler {

    Result<Void> handle(Task task);

}