package test2_synchronized;

public class main {
    public static void main(String[] args) {
        cook c=new cook();
        eater e=new eater();
        c.setName("厨师");
        e.setName("食客");
        c.start();
        e.start();
    }
}
