package test2_阻塞队列;

import java.util.concurrent.BlockingDeque;

public class cook extends Thread{
    BlockingDeque<String> blockingDeque;

    public cook(BlockingDeque<String> blockingDeque) {
        this.blockingDeque = blockingDeque;
    }

    @Override
    public void run() {

    }

}
