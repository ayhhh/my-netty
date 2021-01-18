package testnetty;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.org.apache.bcel.internal.generic.RETURN;
import com.sun.scenario.effect.impl.sw.sse.SSEBlend_SRC_OUTPeer;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.sctp.nio.NioSctpChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.junit.Test;

import javax.swing.text.AbstractDocument;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 手写一个RPC
 * 来回通信，连接数量
 * 拆包、粘包
 * 动态代理、序列化、协议封装
 * 连接池
 * RPC：就像调用本地方法一样去调用远程的方法，面向interface开发
 *
 */
public class MyRPC {


    // 模拟provider端
    @Test
    public void startServer() throws InterruptedException {
        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = boss;

        ServerBootstrap bs = new ServerBootstrap();
        ChannelFuture bind = bs.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(
                                new ServerDecoder(),
                                new ServerRequestHandler());
                    }
                })
                .bind(new InetSocketAddress(9090));
        bind.sync().channel().closeFuture().sync();
    }



    // 模拟consumer端
    @Test
    public void get(){

        new Thread(()->{
            try {
                startServer();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
        System.out.println("server started!!!");

        for(int i = 0;i <100;i++){
            new Thread(()->{
                Car car = proxyGet(Car.class); // 只能通过代理返回
                car.getCar("hello");
            }).start();
        }

        try {
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static <T> T proxyGet(Class<T> clazz){
        // 实现动态代理

        ClassLoader classLoader = clazz.getClassLoader();
        Class<?>[] interfaces = {clazz};

        Object proxy = Proxy.newProxyInstance(classLoader, interfaces, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                // 如何设计我们的consumer对于provider的调用过程
                // 1. 收集调用服务、方法、参数，封装成message
                String serviceName = clazz.getName(); // 服务名字
                String methodName = method.getName(); // 方法名字
                Class<?>[] parameterTypes = method.getParameterTypes(); // 参数类型
                // 封装content
                MyContent myContent = new MyContent();
                myContent.setServiceName(serviceName);
                myContent.setMethodName(methodName);
                myContent.setParameterTypes(parameterTypes);
                myContent.setArgs(args);



                // 2. header: [flag, request ID, size] + body: [content]， 本地要缓存ID
                byte[] msgBody = JSON.toJSONString(myContent).getBytes(); // 序列化为字节数组
                MyHeader myHeader = createHeader(msgBody);
                byte[] msgHeader = JSON.toJSONString(myHeader).getBytes();

                // 注册callback
                CountDownLatch latch = new CountDownLatch(1);
                ResponseHandler.addCallback(myHeader.getRequestID(),()->{
                    latch.countDown();
                });


                // 3. 连接池，取得连接
                ClientFactory factory = ClientFactory.getInstance();
                NioSocketChannel clientChannel = factory.getClient(new InetSocketAddress("localhost",9090));

                // 4. 发送 --> 走IO 出去
                ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(msgHeader.length + msgBody.length);
                buf.writeBytes(msgHeader);
                buf.writeBytes(msgBody);
                ChannelFuture channelFuture = clientChannel.writeAndFlush(buf);
                channelFuture.sync();


                latch.await();
                // 5. 怎么取返回值，怎么将代码执行到这里（睡眠？回调？countdownlatch）

                return null;
            }
        });

        return (T) proxy;
    }

    private static MyHeader createHeader(byte[] msgBody) {
        int size = msgBody.length;
        int f = 0x14141414;
        long id = Math.abs(UUID.randomUUID().getLeastSignificantBits());
        MyHeader myHeader = new MyHeader();
        myHeader.setDataLength(size);
        myHeader.setFlag(f);
        myHeader.setRequestID(id);

        return myHeader;

    }
}

class ServerDecoder extends ByteToMessageDecoder{
    // 被父类中的channelRead方法调用，让子类重写改函数即可
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) throws Exception {
        while (buf.readableBytes() >= 67){ // 是一个消息
            byte[] headerBytes = new byte[67];
            buf.getBytes(buf.readerIndex(),headerBytes); // 不移动指针读
            // buf.readBytes(headerBytes);
            MyHeader header = JSON.parseObject(new String(headerBytes), MyHeader.class);

            MyContent myContent = null;
            if((buf.readableBytes() - 67) >= header.getDataLength()){
                // 可读的长度 >= dataLength，这说明其他包的内容有可能粘到当前包了
                // 先把自己的content取出来
                buf.readBytes(67); // 后面的content是完整的，移动指针
                byte[] contentBytes = new byte[(int) header.getDataLength()];
                buf.readBytes(contentBytes);
                myContent = JSON.parseObject(new String(contentBytes), MyContent.class);

                out.add(new Message(header,myContent));

            } else { // 不够，别读了，跳出循环
                break;
            }
        }
    }
}

class Message{
    private MyHeader header;
    private MyContent content;

    public Message(MyHeader header, MyContent content) {
        this.header = header;
        this.content = content;
    }

    public MyHeader getHeader() {
        return header;
    }

    public MyContent getContent() {
        return content;
    }
}

class ServerRequestHandler extends ChannelInboundHandlerAdapter{
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Message request = (Message) msg;
        //System.out.println("server handler: " + request.getContent().getArgs()[0]);

        // todo 处理业务逻辑

        //写回
        // 在client那一侧也要解决解码问题

        String ioThreadName = Thread.currentThread().getName();
        ctx.executor().execute(new Runnable() {
            @Override
            public void run() {
                String execThread = Thread.currentThread().getName();
                MyResponseBody content = new MyResponseBody();
                content.setRes("io thread: " + ioThreadName + ", exec thread: " + execThread);
                byte[] msgBody = JSON.toJSONString(content).getBytes();

                MyHeader header = new MyHeader();
                header.setRequestID(request.getHeader().getRequestID());
                header.setFlag(0x15151515);
                header.setDataLength(msgBody.length);
                byte[] msgHeader = JSON.toJSONString(header).getBytes();

                ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(msgHeader.length + msgBody.length);
                buf.writeBytes(msgHeader);
                buf.writeBytes(msgBody);

                ctx.writeAndFlush(buf);
            }
        });


    }
}

interface Car{
    void getCar(String msg);
}

class MyResponseBody implements Serializable{
    private String res;

    public String getRes() {
        return res;
    }

    public void setRes(String res) {
        this.res = res;
    }
}

class MyContent implements Serializable{
    private String serviceName;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] args;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public Class<?>[] getParameterTypes() {
        return parameterTypes;
    }

    public void setParameterTypes(Class<?>[] parameterTypes) {
        this.parameterTypes = parameterTypes;
    }

    public Object[] getArgs() {
        return args;
    }

    public void setArgs(Object[] args) {
        this.args = args;
    }
}

class MyHeader implements Serializable{
    // 通信上的协议：1.消息类型；2.UUID；3.data length
    private int flag;
    private long requestID;
    private long dataLength;

    public int getFlag() {
        return flag;
    }

    public void setFlag(int flag) {
        this.flag = flag;
    }

    public long getRequestID() {
        return requestID;
    }

    public void setRequestID(long requestID) {
        this.requestID = requestID;
    }

    public long getDataLength() {
        return dataLength;
    }

    public void setDataLength(long dataLength) {
        this.dataLength = dataLength;
    }
}

class ClientPool{
    NioSocketChannel[] clients;
    Object[] locks;

    public ClientPool(int size){
        clients = new NioSocketChannel[size]; // init, 连接是空的
        locks = new Object[size]; // 每个连接对应的锁
        for(int i=0;i<size;i++){
            locks[i] = new Object();
        }
    }
}

class ClientFactory{
    int poolSize = 1;
    NioEventLoopGroup clientWorker = null;
    // 用于随机从pool中下标
    Random random = new Random();
    // 一个consumer可以连接很多的provider，每一个provider都有自己的pool，<K,V>
    private ConcurrentHashMap <InetSocketAddress,ClientPool> outboxes = new ConcurrentHashMap<>();
    private static volatile ClientFactory _instance = null;
    private ClientFactory(){
    }

    public static ClientFactory getInstance(){
        if(_instance == null) {
            synchronized (ClientFactory.class){
                if(_instance == null){
                    _instance = new ClientFactory();
                }
            }
        }
        return _instance;
    }
    public synchronized NioSocketChannel getClient(InetSocketAddress address){
        ClientPool clientPool = outboxes.get(address);
        if(clientPool == null){
            // 必须使用putIfAbsent方法
            outboxes.putIfAbsent(address, new ClientPool(poolSize));
            clientPool = outboxes.get(address);
        }

        int i = random.nextInt(poolSize);
        if(clientPool.clients[i] != null && clientPool.clients[i].isActive()){
            return clientPool.clients[i];
        }

        synchronized (clientPool.locks[i]){
            return clientPool.clients[i] = create(address);
        }

    }
    private NioSocketChannel create(InetSocketAddress address) {
        // 基于netty的客户端创建方式
        clientWorker = new NioEventLoopGroup(1);

        Bootstrap bs = new Bootstrap();
        ChannelFuture connect = bs.group(clientWorker)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new ClientResponse()); // 解决给谁的
                    }
                }).connect(address);
        try {
            NioSocketChannel channel = (NioSocketChannel) connect.sync().channel();
            return channel;
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }
    }

}

class ClientResponse extends ChannelInboundHandlerAdapter{
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf buf = (ByteBuf) msg;
        if(buf.readableBytes() >= 67){ // 是一个消息
            byte[] headerBytes = new byte[67];
            buf.readBytes(headerBytes);
            MyHeader header = JSON.parseObject(new String(headerBytes), MyHeader.class);
            // 拿到的uuid，应该去一个map里面找对应的CountDownLatch
            // latch.countDown()往前走，对应的线程就可以继续执行了
            ResponseHandler.runCallback(header.getRequestID());


        }
        super.channelRead(ctx, msg);
    }
}

class ResponseHandler{
    private static ConcurrentHashMap<Long, Runnable> mapping = new ConcurrentHashMap<>();

    public static void addCallback(long requestID, Runnable callback){
        mapping.putIfAbsent(requestID,callback);
    }

    public static void runCallback(long requestID){
        Runnable runnable = mapping.get(requestID);
        runnable.run();
        // remove callback
        removeCallback(requestID);
    }

    private static void removeCallback(long requestID) {
        mapping.remove(requestID);
    }

}