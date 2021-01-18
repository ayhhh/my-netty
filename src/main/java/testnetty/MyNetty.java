package testnetty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;
import org.junit.Test;
import org.omg.CORBA.PUBLIC_MEMBER;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;

/**
 * netty的初级使用练习
 * NIO: Channel, ByteBuffer, Selector
 * Netty: 对上述三个类进行了一定的封装ByteBuffer -> ByteBuf【pool】
 * Channel和ByteBuffer的关系
 * 为什么要Buffer？主要是为了性能考虑，少使用几次系统调用
 */

public class MyNetty {
    @Test
    public void myByteBuf(){
        /**
         * 得到一个ByteBuf
         * 方法一：使用ByteBufAllocator
         */
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(8,20);
        ByteBufAllocator.DEFAULT.heapBuffer(8,20);
        /**
         * 得到一个ByteBuf
         * 方法二：UnpooledByteBufAllocator
         */
        UnpooledByteBufAllocator.DEFAULT.heapBuffer(8,20);
        UnpooledByteBufAllocator.DEFAULT.directBuffer(8,20);

        print(buf);

        buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);
        buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);
        buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);
        buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);
        buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);

    }

    public static void print(ByteBuf buf){
        System.out.println("isReadable: "+ buf.isReadable()); // 是否可读
        System.out.println("readerIndex: " + buf.readerIndex()); // 可以从哪里开始读
        System.out.println("readableBytes: " + buf.readableBytes()); // 可以读多少字节

        System.out.println("isWritable: " + buf.isWritable());
        System.out.println("writerIndex: "+ buf.writerIndex());
        System.out.println("writableBytes: " + buf.writableBytes());

        System.out.println("capacity: " + buf.capacity()); // 当前容量
        System.out.println("maxCapacity: " + buf.maxCapacity()); // 最大容量
        System.out.println("isDirect:" + buf.isDirect()); // 是否是堆外分配
        System.out.println("==============================================");
    }

    /**
     * 客户端
     * 连接别人
     * 1. 主动发送数据
     * 2. 别人什么时候给我发呢？event --> selector
     */
    @Test
    public void loopExecutor() throws IOException {
        // 这个其实也是一个线程池
        NioEventLoopGroup selector = new NioEventLoopGroup(1);
        selector.execute(()->{
            System.out.println("hello world.");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        System.in.read();
    }

    /*@Test
    public void clientMode() throws InterruptedException {
        NioEventLoopGroup group = new NioEventLoopGroup(1);

        // 客户端模式
        NioSocketChannel client = new NioSocketChannel();
        group.register(client); // 注册

        // 响应式的
        ChannelPipeline pipeline = client.pipeline();// 如果产生了事件，我应该做哪些事情
        pipeline.addLast(new MyInHandler()); // 需要自定义handler

        // reactor 异步的特征
        ChannelFuture connect = client.connect(new InetSocketAddress("127.0.0.1", 9090));
        ChannelFuture sync = connect.sync();// 等待连接成功
        ByteBuf buf = Unpooled.copiedBuffer("hello server".getBytes());
        ChannelFuture send = client.writeAndFlush(buf);// 也是异步的
        send.sync();

        sync.channel().closeFuture().sync(); // 等待别人关闭
        System.out.println("client over...");
    }*/

    @Test
    public void client() throws InterruptedException {
        NioEventLoopGroup thread = new NioEventLoopGroup(1);
        NioSocketChannel client = new NioSocketChannel();
        thread.register(client);
        ChannelPipeline pipeline = client.pipeline();
        pipeline.addLast(new MyHandler());
        ChannelFuture connect = client.connect(new InetSocketAddress("192.168.46.129", 9999));
        connect.sync();

        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(8, 24);
        byteBuf.writeBytes("hello".getBytes());
        client.writeAndFlush(byteBuf);

        connect.channel().closeFuture().sync();
    }

    @Test
    public void server() throws Exception {
        NioEventLoopGroup thread = new NioEventLoopGroup(1);
        NioServerSocketChannel server = new NioServerSocketChannel();
        ChannelPipeline pipeline = server.pipeline();
        pipeline.addLast(new MyAcceptHandler(thread, new MyHandler()));
        thread.register(server); // 注册
        ChannelFuture bind = server.bind(new InetSocketAddress(9999));// 绑定

        bind.sync().channel().closeFuture().sync();
    }

    @Test
    public void testNettyClient() throws InterruptedException {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Bootstrap bs = new Bootstrap(); // 引导类可以减少很多代码量
        ChannelFuture bind = bs.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new MyHandler());
                    }
                })
                .connect(new InetSocketAddress("192.168.46.129", 9999));
        bind.sync().channel().closeFuture().sync();
    }

    @Test
    public void testNettyServer(){
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        ServerBootstrap bs = new ServerBootstrap();
        bs.group(group,group);
        bs.channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioServerSocketChannel>() {
                    @Override
                    protected void initChannel(NioServerSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new MyHandler());
                    }
                }).bind(new InetSocketAddress(9999));
    }



}


class InitHandler extends ChannelInboundHandlerAdapter{
    /**
     * 这个类是抽象类
     * 这里应该是一个抽象方法，让其他人去现实这个方法，自定义初始化handler的内容
     * 这个init()会在channelRegistered中调用
     */
    public void init(){
        // todo
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        init();
    }
}

class MyHandler extends ChannelInboundHandlerAdapter{
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf buf = (ByteBuf) msg;
        CharSequence str = buf.readCharSequence(buf.readableBytes(), CharsetUtil.UTF_8);
        System.out.println(str);

    }
}

class MyAcceptHandler extends ChannelInboundHandlerAdapter{

    private NioEventLoopGroup thread;
    private MyHandler handler;

    public MyAcceptHandler(NioEventLoopGroup thread, MyHandler handler) {
        this.thread = thread;
        this.handler = handler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        SocketChannel client = (SocketChannel) msg;
        // 拿到client之后需要注册到NioEventLoopGroup里面去
        thread.register(client);
        ChannelPipeline pipeline = client.pipeline();
        pipeline.addLast(handler); // 它的处理器
    }
}
