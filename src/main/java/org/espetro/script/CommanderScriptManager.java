package org.espetro.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;
import org.espetro.Espetro;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal scheduler used by KubeJS commander skill helper APIs.
 *
 * <p>Commander skills are registered in KubeJS startup scripts and implemented
 * in KubeJS server scripts. This class deliberately does not load script files
 * or data-pack skill definitions.</p>
 */
public class CommanderScriptManager {
    private static CommanderScriptManager INSTANCE;

    private final List<ScheduledTask> scheduledTasks = new ArrayList<>();
    private long currentTick;

    private CommanderScriptManager() {
        INSTANCE = this;
    }

    public static CommanderScriptManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CommanderScriptManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new CommanderScriptManager();
    }

    public void scheduleJavaTask(int delayTicks, Runnable runnable) {
        if (runnable != null) {
            scheduledTasks.add(new JavaScheduledTask(currentTick + Math.max(0, delayTicks), runnable));
        }
    }

    public void scheduleScriptCallback(int delayTicks, Function callback, CommanderScriptEvent event) {
        if (callback != null && event != null) {
            scheduledTasks.add(new ScriptScheduledTask(currentTick + Math.max(0, delayTicks), callback, event));
        }
    }

    public void onServerTick() {
        currentTick++;
        if (scheduledTasks.isEmpty()) {
            return;
        }

        List<ScheduledTask> due = new ArrayList<>();
        scheduledTasks.removeIf(task -> {
            if (task.runAtTick() <= currentTick) {
                due.add(task);
                return true;
            }
            return false;
        });

        for (ScheduledTask task : due) {
            try {
                task.run();
            } catch (Exception e) {
                Espetro.LOGGER.error("KubeJS 指挥官技能调度任务执行失败", e);
            }
        }
    }

    public void reset() {
        scheduledTasks.clear();
        currentTick = 0;
    }

    private interface ScheduledTask {
        long runAtTick();

        void run();
    }

    private record JavaScheduledTask(long runAtTick, Runnable runnable) implements ScheduledTask {
        @Override
        public void run() {
            runnable.run();
        }
    }

    private record ScriptScheduledTask(long runAtTick,
                                       Function callback,
                                       CommanderScriptEvent event) implements ScheduledTask {
        @Override
        public void run() {
            Context cx = Context.enter();
            cx.setApplicationClassLoader(CommanderScriptManager.class.getClassLoader());
            Scriptable scope = callback.getParentScope();
            Object eventObject = Context.javaToJS(cx, event, scope);
            callback.call(cx, scope, scope, new Object[] {eventObject});
        }
    }
}
