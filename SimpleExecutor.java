package executor;

/*
 *@Author  LXC BlueProtocol
 *@Since   2022/5/18
 */


import exception.RejectTaskException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SimpleExecutor {

    // 线程池的基本属性:
    // 核心线程池大小
    private volatile int corePoolSize;

    // 最大线程池大小
    private volatile int maxPoolSize;

    // 线程的最大存活时间
    private volatile long keepAliveTime;

    // 阻塞队列
    private BlockingQueue<Runnable> taskQueue = new LinkedBlockingDeque<>();

    private HashSet<Worker> workers = new HashSet<>();

    // 当前线程的状态
    private AtomicInteger State = new AtomicInteger();

    // 当前线程的数量
    private AtomicInteger workCount = new AtomicInteger();

    // 记录当前线程池总共完成了多少个任务
    private Long finishedTask = Long.valueOf("0");

    // 全局锁
    private Lock staticLock = new ReentrantLock();

    // 线程池的简单状态
    // 运行
    private final int RUNNING = 0;

    // 停止
    private final int STOPPED = 1;


    public SimpleExecutor(int corePoolSize, int maxPoolSize, long keepAliveTime) {
        if (corePoolSize < 0 || maxPoolSize <= 0 || keepAliveTime < 0 || maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException();
        }
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
    }

    // run方法会不断在阻塞队列里面拿他的任务
    private void runWork(Worker worker) {
        if (worker == null) throw new NullPointerException("The worker is not to Null");
        Thread wt = worker.thread;
        Runnable task = worker.firstTask;// worker的首要任务，这个任务会在后续被执行
        worker.firstTask = null;
        try {
            while ((task != null) || (task = getTask()) != null) {
                // 判断一下工作线程，如果
                if (wt.isInterrupted()) {
                    System.out.println("This task is interrupted.");
                    return;
                }
                if (State.get() == STOPPED) {
                    System.out.println("This thread pool has stopped.");
                }
                task.run();
                task = null;
                worker.completeTasks++;
            }
        } finally {
            // worker的正常退出流程
            staticLock.lock();
            try {
                workers.remove(worker);
                if (castDecreaseWorkCount()) {
                    finishedTask += worker.completeTasks;
                }
            } finally {
                staticLock.unlock();
            }
        }
    }

    private boolean castDecreaseWorkCount() {
        return workCount.compareAndSet(workCount.get(), workCount.get() - 1);
    }

    // 向线程池去提交任务
    public void executor(Runnable task) throws RejectTaskException {
        if (task == null) {
            throw new NullPointerException("The task can not be null.");
        }
        // 检查当前线程是否是运行状态
        if (State.get() == RUNNING) {
            // 运行状态有两种情况
            // 1、直接创建一个Work,把当前的task作为firstTask即可
            // 2、Work的数量已经达到了最大的限制了，我们不能再创建Worker了，只能往任务队列里面去放
            if (workCount.get() < corePoolSize && andWork(task, true)) {
                return;
            }
            if (workCount.get() < maxPoolSize && andWork(task, false)) {
                return;
            }
            // 剩下的只能往任务队列里面去存放了
            // 因为是并发场景，其他外部线程或者是其他Work会操作任务队列
            // 为了保证安全，所以需要加锁
            staticLock.lock();
            try {
                // 这里我们需要去看一下，有可能我们外部线程工作，或者是我们的线程调用了我们的Stop方法
                if (State.get() == RUNNING) {
                    if (!taskQueue.offer(task)) {
                        throw new RejectTaskException("将提交的任务添加到任务队列里面的时候失败");
                    }
                }

            } finally {
                staticLock.unlock();
            }

        } else {// 当前线程为停止状态，拒绝接收
            throw new RejectTaskException("线程池已经处于停止状态，不能接收新的任务");
        }
    }

    private boolean andWork(Runnable firstTask, boolean isCore) {
        if (State.get() == STOPPED) return false;
        out:
        // 不一定是Running
        while (true) {
            if (State.get() == STOPPED) return false;
            while (true) { // 判断当前线程池的工作数量是否已经超过阈值了
                if (workCount.get() > (isCore ? corePoolSize : maxPoolSize)) {
                    // 线程池不再运行创建新的work了,添加失败
                    return false;
                }
                // 到了这里就说明我们可以创建新的work了
                if (!casIncreaseWorkCount()) {
                    continue out;
                }
                // 到了这里我们就添加成功了
                break out;
            }
        }

        // 实际添加Worker的操作
        Worker worker;
        staticLock.lock();
        try {
            if (State.get() == STOPPED) return false;
            worker = new Worker(firstTask);
            Thread wt = worker.thread;
            if (wt != null) {
                if (wt.isAlive()) {
                    // 我们在创建一个worker的时候，这个时候我们的worker的工作线程应该在这里启动
                    // 如果我们的线程之前已经启动了，那么就不是我们的操作了,抛出异常
                    throw new IllegalStateException("线程非法操作");
                }
                wt.start();
                workers.add(worker);
            }
        } finally {
            staticLock.unlock();
        }

        return true;
    }

    private boolean casIncreaseWorkCount() {
        return workCount.compareAndSet(workCount.get(), workCount.get() + 1);
    }

    public Runnable getTask() {
        if (State.get() == STOPPED) return null;
        Runnable task = null;
        while (true) {
            try {
                if (State.get() == STOPPED) return null;
                // 以核心线程的角色去获取任务
                if (workCount.get() <= corePoolSize) {
                    // core
                    task = taskQueue.take();
                } else {
                    // not core
                    task = taskQueue.poll(keepAliveTime, TimeUnit.MILLISECONDS);
                }
                if (task != null) {
                    return task;
                }
            } catch (InterruptedException interrupted) {
                // 这个异常是终止的意思，就是当其他线程去调用Stop的时候，会给我们的每一个线程发出一个终止的信号。
                // 一旦发出信号就返回null，不做其他操作了
                return null;
            }

        }
    }

    public void stop() {
        staticLock.lock();
        try {
            setState(STOPPED);
            interruptAllWorkers();
        } finally {
            staticLock.unlock();
        }
    }

    private void setState(int targetState) {
        while (true) {
            if (State.get() == targetState) {
                break;
            }
            if (State.compareAndSet(State.get(), targetState)) {
                break;
            }
        }
    }

    private void interruptAllWorkers() {
        staticLock.lock();
        try {
            for (Worker worker : workers) {
                if (!worker.thread.isInterrupted()) {
                    worker.thread.isInterrupted();
                }
            }
        } finally {
            staticLock.unlock();
        }
    }

    public List<Runnable> stopNow() {
        List<Runnable> remains = new ArrayList<>();
        staticLock.lock();
        try {
            if (!taskQueue.isEmpty()) {
                taskQueue.drainTo(remains);
            }
            while (!taskQueue.isEmpty()) {
                remains.add(taskQueue.poll());
            }
        } finally {
            staticLock.unlock();
        }
        return remains;
    }

    public long getFinishTaskCount() {
        staticLock.lock();
        try {
            long already = finishedTask;
            for (Worker worker : workers) {
                already += worker.completeTasks;
            }
            return already;
        } finally {
            staticLock.unlock();
        }
    }


    private final class Worker implements Runnable {
        final Thread thread;
        Runnable firstTask;
        volatile long completeTasks;

        Worker(Runnable firstTask) {
            if (firstTask == null) throw new NullPointerException("The FirstTask Is Not To Null");
            this.firstTask = firstTask;
            thread = new Thread(this);
        }

        @Override
        public void run() {
            runWork(this);
        }


    }

    // 选择 判断 写程序 伪代码PV操作 分析题（给我数据，把我们的数据走一遍，就像我们的作业一样，同步作业，课后练习，实验技术原理也要吃透）
    //


}
