package test2_synchronized;

public class eater extends Thread{
    @Override
    public void run(){
        while(true){
            synchronized (desk.lock){
                if(desk.count==0){
                    break;
                }else{
                    if(desk.hasFood==0){
                        try {
                            desk.lock.wait();
                        } catch (InterruptedException e) {

                            e.printStackTrace();
                        }

                    }else {
                        desk.count--;
                        System.out.println("吃了一碗饭，还剩"+desk.count);
                        desk.hasFood=0;
                        desk.lock.notify();

                    }
                }
            }
        }
    }
}
