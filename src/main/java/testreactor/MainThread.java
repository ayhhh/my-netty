package testreactor;

public class MainThread {
    public static void main(String[] args) {
        // 这里不做关于IO和业务的事情

        // 1.创建IO Thread
        // 乞丐版，创建的线程组中每个线程都负责读写
        SelectorThreadGroup boss = new SelectorThreadGroup(3);
        SelectorThreadGroup worker = new SelectorThreadGroup(3);
        boss.setWorker(worker);
        // 2.把监听的server注册到某一个Thread（selector）
        boss.bind(9999); // 绑定端口号
    }
}
