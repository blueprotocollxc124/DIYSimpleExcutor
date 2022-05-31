package test;

/*
 *@Author  LXC BlueProtocol
 *@Since   2022/5/31
 */


import exception.RejectTaskException;
import executor.SimpleExecutor;

import java.sql.Time;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyTest {
    public static void main(String[] args) throws RejectTaskException, InterruptedException {
        SimpleExecutor simpleExecutor = new SimpleExecutor(1, 1, 0);
        for (int i = 0; i < 20; i++) {
            simpleExecutor.executor(new RunnableTask(i));
        }
        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("完成的线程数量为：" + simpleExecutor.getFinishTaskCount());
        simpleExecutor.stop();
//        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingDeque<>());
//        for (int i = 0; i < 20; i++) {
//
//            executor.execute(new RunnableTask(i));
//        }
//        TimeUnit.SECONDS.sleep(2);
//        System.out.println("完成的任务数是："+executor.getTaskCount());
//        executor.shutdown();
    }
}

class RunnableTask implements Runnable {


    int name;

    public RunnableTask(int name) {
        this.name = name;
    }

    @Override
    public void run() {
        System.out.println("线程任务" + name + "执行");
    }
}
