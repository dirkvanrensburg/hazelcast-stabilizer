package com.hazelcast.stabilizer.worker.commands;

import java.io.Serializable;

public class CommandRequest implements Serializable {

    public static final long serialVersionUID = 0l;

    public long id;
    public Command task;

    @Override
    public String toString() {
        return "TestCommandRequest{" +
                "id=" + id +
                ", task=" + task +
                '}';
    }
}
