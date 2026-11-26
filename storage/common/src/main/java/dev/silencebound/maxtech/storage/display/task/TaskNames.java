package dev.silencebound.maxtech.storage.display.task;

/**
 * How a logic node names a task (M7C item 15): scope, board and task in one text, so a compiled watch or action holds
 * one string. Pure; any thread.
 */
public final class TaskNames {
    private TaskNames() {
    }

    /** One text for a board's task: {@code n} or {@code t} for the scope, the board and the task, one per line. */
    public static String logicName(boolean networkScope, String board, String task) {
        return (networkScope ? "n" : "t") + "\n" + Task.clean(board, TaskBoardData.MAX_BOARD_NAME) + "\n"
                + Task.clean(task, Task.MAX_NAME);
    }

    /** A logic name readable in logs: "board / task". */
    public static String describe(String logicName) {
        String[] parts = logicName.split("\n", 3);
        return parts.length == 3 ? parts[1] + " / " + parts[2] : logicName;
    }
}
