package com.taskqueue.handler;

import com.taskqueue.common.Result;
import com.taskqueue.model.Task;

@FunctionalInterface
public interface TaskHandler {

    Result<Void> handle(Task task) throws Exception;

}
