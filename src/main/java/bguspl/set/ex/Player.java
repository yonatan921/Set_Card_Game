package bguspl.set.ex;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    //private queue (capacity = 3) public function of push,pop,get size
    private ArrayBlockingQueue<Integer> queue; 

    private int tokensPlaced;

    private Dealer dealer;

    private boolean allowedToPlaceTokens;

    private long freezeMillis;

    private Random rnd;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        queue = new ArrayBlockingQueue<>(3);
        tokensPlaced = 0;
        this.dealer = dealer;
        allowedToPlaceTokens = false;
        freezeMillis = 0;
        rnd = new Random();
    }

    public void setAllowedTokens(boolean toSet) {
        allowedToPlaceTokens = toSet;
    }

    public int incrementScore() {
        return ++score;
    }

    public void setTokensPlaced(int tokensPlaced) {
        this.tokensPlaced = tokensPlaced;
    }

    public int getTokensPlaced() {
        return tokensPlaced;
    }

    public synchronized void handleFreeze(long millis) {
        freezeMillis = millis;
        setAllowedTokens(false);
        notifyAll();
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            // TODO implement main player loop
            /*
             * if(table.countCards < rows*columns(=12))
             *  wait until = 12
             * else
             *  wait until key press
             */
            // System.out.println(this);
            synchronized(this) {
                try {
                    // Thread.currentThread().wait();
                    wait();
                    queuePop();
                } catch(InterruptedException e) {
                    //handle key press
                }
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
            while (!terminate) {
                // TODO implement player key press simulator
                // try { // noSuchElementException
                //     synchronized (this) { wait(env.config.tableDelayMillis * 12); }
                // } catch (InterruptedException ignored) {}
                // pickRandomSlot();
                // queuePop();
            }
            System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    private void pickRandomSlot() {
        Random rnd = new Random();
        int slot = rnd.nextInt(12);
        System.out.println(slot);
        if(allowedToPlaceTokens) {
            queue.add(slot);
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        // TODO implement
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(allowedToPlaceTokens) {
            queue.add(slot);
            synchronized(this) {
                notifyAll();
            }
        }
    }

    private void queuePop() {
        Integer slotNum = queue.remove();
        boolean tokenStateAtSlot = table.getPlayerTokenState(id, slotNum);
        if(tokenStateAtSlot) {
            tokensPlaced--; // should be synced
            table.removeToken(id, slotNum);
        }
        else {
            if(tokensPlaced < 3) {
                tokensPlaced++;
                table.placeToken(id, slotNum);
                if(tokensPlaced == 3){
                    //submit set to dealer
                    // System.out.println("placed 3");
                    dealer.submitedSet(id);
                    //wait for reward/penalty
                    synchronized(this) {
                        try {
                            wait();
                        } catch(InterruptedException e) {

                        }
                    }
                    penalty(); //freeze/penalty
                }
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        setTokensPlaced(0);
        //reward player with a point + freeze
        int scoreToUpdate = incrementScore();
        env.ui.setScore(id, score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO implement
        while(freezeMillis > 0) {
            env.ui.setFreeze(id, freezeMillis);
            try {
                playerThread.sleep(1000); //MIGHT BE PROBLEMATIC
            } catch (InterruptedException e) {}
            freezeMillis -= 1000;
        }
        env.ui.setFreeze(id, freezeMillis);
        //enable presses
        setAllowedTokens(true);
    }

    public int score() {
        return score;
    }

    public int getId() {
        return id;
    }

    public void removeAllTokens() {
        tokensPlaced = 0;
        queue.clear();
    }
}
