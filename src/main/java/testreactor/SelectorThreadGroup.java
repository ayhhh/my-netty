package testreactor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

public class SelectorThreadGroup {
    SelectorThread[] threads;
    ServerSocketChannel server = null;
    // 需要原子类，因为有并发问题
    AtomicInteger xid = new AtomicInteger(0);

    public SelectorThreadGroup(){

    }

    /**
     * 有参构造
     * @param num: 线程数
     */
    public SelectorThreadGroup(int num){
        threads = new SelectorThread[num];
        for (int i = 0; i < num; i++) {
            threads[i] = new SelectorThread(this);
            new Thread(threads[i]).start();
        }
    }

    public void bind(int port){
        try {
            server = ServerSocketChannel.open();
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(port));

            // server注册给谁？
            SelectorThread selectorThread = nextSelector();// 找到下一个
            register(server,selectorThread);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 注册channel
    public SelectorThread nextSelector() {
        int index = xid.getAndIncrement() % threads.length;
        return threads[index];
    }

    public void register(Channel channel, SelectorThread selectorThread){
        /**
         * 方法二：扔到目标selector的队列里面去
         */
        try {
            // 1. 通过队列传递数据，并且这一行不会阻塞
            selectorThread.queue.put(channel);
            // 2. 唤醒，让对应的线程自己去完成注册
            selectorThread.selector.wakeup();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        /**
        // 方法一：由于前面的每个SelectorThread的accept()方法会一直阻塞，所以得先wakeup()
        if(channel instanceof ServerSocketChannel){
            ServerSocketChannel server = (ServerSocketChannel) channel;
            try {
                // 但是，这个时候有一种可能：
                // wakeup()这行代码执行完成之后，当前线程时间片结束，而目标SelectorThread又走了并且再次阻塞了。咋办？
                // 所以wakeup()得放在下边
                server.register(selectorThread.selector, SelectionKey.OP_ACCEPT);
                // 放下面仍然有问题，因为register是阻塞的，所以可能根本没法执行到下一行
                // selectorThread.selector.wakeup();

                //  所以只能：
                //  方法二：往队列里面扔
            } catch (ClosedChannelException e) {
                e.printStackTrace();
            }
        } else {

        }
         */
    }
}
