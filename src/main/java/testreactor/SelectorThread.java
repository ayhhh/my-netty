package testreactor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

public class SelectorThread implements Runnable{
    // 每线程对应一个selector，
    // 多线程情况下，该主机，该程序的并发客户端被分配到多个selector上
    // 每个客户端只绑定到其中一个selector
    Selector selector = null;

    LinkedBlockingQueue<Channel> queue = new LinkedBlockingQueue<>();

    /**
     * 持有一个它对应的group的引用，作用是用于往其他selector上面去添加channel。
     */
    SelectorThreadGroup group = null;

    SelectorThread(SelectorThreadGroup group){
        this.group = group;
        try {
            selector = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void run() {
        // Loop
        while (true){
            try {
                // 1.select()
                int nums = selector.select(); // 阻塞
                // 2.处理selectKeys
                if(nums > 0){
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = keys.iterator();
                    while (iterator.hasNext()){
                        SelectionKey key = iterator.next();
                        // 这里移除的原因是，这个selectedKeys只有一个，并不是每次拿都新生成的，所以用了就要删除
                        iterator.remove();
                        if(key.isAcceptable()){ // 接收了之后，要扔给其他selector
                            acceptHandler(key);
                        } else if(key.isReadable()){
                            readHandler(key);
                        } else if(key.isWritable()){
                            writeHandler(key);
                        }
                    }
                }

                // 3.处理一些task
                if(!queue.isEmpty()){
                    try {
                        Channel channel = queue.take();
                        if(channel instanceof ServerSocketChannel){
                            ServerSocketChannel server = (ServerSocketChannel) channel;
                            server.register(selector,SelectionKey.OP_ACCEPT);
                        } else if(channel instanceof SocketChannel){
                            SocketChannel client = (SocketChannel) channel;
                            ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
                            client.register(selector,SelectionKey.OP_READ,buffer);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }


            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void writeHandler(SelectionKey key) {
    }

    private void readHandler(SelectionKey key) {
        ByteBuffer buffer = (ByteBuffer) key.attachment(); // 附加对象
        SocketChannel client = (SocketChannel) key.channel();
        buffer.clear();
        while(true){
            try {
                int num = client.read(buffer);
                if(num > 0){
                    buffer.flip(); // 翻转，然后直接写回
                    while(buffer.hasRemaining()){
                        client.write(buffer);
                    }
                    buffer.clear();
                } else if(num == 0){
                    break;
                } else { // num<0, 客户端断开了
                    System.out.println("client: "+ client.getRemoteAddress()+" closed!");
                    key.cancel(); // 从多路复用器中取消掉
                    client.close();
                    break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void acceptHandler(SelectionKey key) {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        try {
            SocketChannel client = server.accept();
            client.configureBlocking(false);

            // 需要扔给worker selector
            SelectorThread nextSelector = group.worker.nextSelector();
            group.worker.register(client,nextSelector);

        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
