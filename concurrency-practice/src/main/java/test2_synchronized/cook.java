package test2_synchronized;

public class cook extends Thread{
    @Override
    public void run(){
        while(true){
            synchronized (desk.lock){
                if(desk.count==0) {
                    break;
                }else{
                    if(desk.hasFood==1){
                        try {
                            desk.lock.wait();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }else{
                        desk.hasFood=1;
                        System.out.println("做了一碗饭");
                        desk.lock.notify();
                    }
                }
            }
        }
    }
}
