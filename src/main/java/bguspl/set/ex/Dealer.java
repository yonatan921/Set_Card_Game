package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.Util;

import java.io.InterruptedIOException;
import java.lang.ProcessBuilder.Redirect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.logging.Level;

import javax.naming.InterruptedNamingException;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private long milliseconds;

    private final long MINUTE = 5000;

    private Thread dealerThread;

    private Semaphore lock = new Semaphore(1, true);

    private int playerSubmittedSet;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        reshuffleTime = System.currentTimeMillis() + MINUTE;
        milliseconds = MINUTE;
        playerSubmittedSet = -1;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        for (Player player : players) {
            Thread pThread = new Thread(player, "player" + player.getId());
            pThread.start();
        }
        
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
               
        System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
    
        }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    long lastSecond;
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime + 1500) { // set reshuffleTime = 60sec
            // long lastSecond = System.currentTimeMillis();
            //IMPROVE TIMER
            if(milliseconds != MINUTE) {
                sleepUntilWokenOrTimeout();
            }
            if(milliseconds == MINUTE || (System.currentTimeMillis() - lastSecond)/1000 == 1) //checks if 1 second passed, updates only if it did
                updateTimerDisplay(false);
            if(milliseconds < 0) {break;}
            // removeCardsFromTable();
            // placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        // TODO implement
        //interrput!
        terminate = true;
        Arrays.stream(players).forEach(Player::terminate);
        //for every player: player.terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks if any cards should be removed from the table and returns them to the deck.
     */
    private void removeCardsFromTable(int[] cardsToRemove) {
        // TODO implement
        // remove the tokens on those cards
        for(int i = 0; i < cardsToRemove.length; i++) {
            table.removeCardsFromTable(cardsToRemove[i], players);
            // try {
            //     Thread.currentThread().sleep(env.config.tableDelayMillis);
            // } catch(InterruptedException ex) {}
        }

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO implement
        /*
         * reshuffle
         *  while(deck > 0 & numOfCardsToPlace >0)
         *  insert to one of the empty slots the last card in the deck
         *  table.placeCard(int card, int slot)
         */

        Collections.shuffle(deck);
        Integer[] slotToCard = table.getSlotToCard();
        //MAGIC NUMBER - 11
        List<Integer> li = IntStream.rangeClosed(0, 11).boxed().collect(Collectors.toList());
        Collections.shuffle(li);
        for(int slotNum = 0; slotNum < li.size() && deck.size() > 0; slotNum++) {
            if(slotToCard[li.get(slotNum)] == null) {
                //remove last card from deck
                Integer cardNum = deck.remove(deck.size() - 1);
                // table.placeCard(int card,int slot)
                table.placeCard(cardNum, li.get(slotNum));
                try{
                    //MAGIC NUMBER - 200
                    Thread.currentThread().sleep(env.config.tableDelayMillis);
                } catch(InterruptedException ex) {}
            }
        }
        Arrays.stream(players).forEach(p -> p.setAllowedTokens(true));
        reshuffleTime = System.currentTimeMillis() + MINUTE;
        lastSecond = System.currentTimeMillis(); 
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {
        // TODO implement
        try {
            // Thread.currentThread().sleep(1000);
            wait(1000);
            if(playerSubmittedSet != -1) {
                // System.out.printf("Info: Thread %s submitted set.%n", Thread.currentThread().getName());
                // System.out.printf("player who submitted set: " + playerSubmittedSet);
                Integer[] setTokens = table.playerSetTokens(playerSubmittedSet);
                long numOfActualTokens = Arrays.stream(setTokens).filter(Objects::nonNull).count();
                Player player = players[playerSubmittedSet];
                
                if(numOfActualTokens == 3) { //MAGIC NUMBER
                    int[] setTokensConverted = new int[3];
                    for(int i = 0; i < 3; i++) {
                        setTokensConverted[i] = (int)setTokens[i];
                    }
                    boolean isValidSet = env.util.testSet(setTokensConverted);
                    if(isValidSet) {
                        removeCardsFromTable(setTokensConverted);
                        placeCardsOnTable();
                        player.point();
                        player.handleFreeze(env.config.pointFreezeMillis);
                        updateTimerDisplay(true);
                    } else {
                        player.setTokensPlaced((int)numOfActualTokens);
                        player.handleFreeze(env.config.penaltyFreezeMillis);
                    }    
                } else {
                    player.freeFromWait();
                }
                playerSubmittedSet = -1;
            }
        } catch(InterruptedException ex){
            //handle interrput (check set...)
            System.out.println("info: got interrupted"); //remove latar
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
        if((System.currentTimeMillis() - lastSecond)/1000 == 1) {
            lastSecond = System.currentTimeMillis(); }
        if(reset || milliseconds < 0) {
            reshuffleTime = System.currentTimeMillis() + MINUTE;
            milliseconds = MINUTE;
        } else if(milliseconds >= 0){
            env.ui.setCountdown(milliseconds, false);
            milliseconds -= 1000;
        }
        // if(!reset) {
        //     if(milliseconds >= 0) {
        //         env.ui.setCountdown(milliseconds, false);

        //         milliseconds -= 1000;
        //     } else {
        //         reshuffleTime = System.currentTimeMillis() + MINUTE;
        //         milliseconds = MINUTE;
        //     }
        // } 
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        if(!terminate) {
        // TODO implement
        //remove from table - insert back to deck
        //MAGIC NUMBER - 11
            Arrays.stream(players).forEach(p -> p.setAllowedTokens(false));
            table.removeAllTokens();
            Arrays.stream(players).forEach(Player::removeAllTokens);
            List<Integer> li = IntStream.rangeClosed(0, 11).boxed().collect(Collectors.toList());
            Collections.shuffle(li);
            for(int i = 0; i < li.size(); i++) {
                deck.add(table.getCardInSlot(li.get(i)));
                table.removeCard(li.get(i));
                try {
                    Thread.currentThread().sleep(env.config.tableDelayMillis);
                } catch(InterruptedException ex) {}
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
    }

    public void submitedSet(int playerIdSubmitted) {
        boolean acquired = false;
        try {
            lock.acquire();
            acquired = true;
        } catch(InterruptedException ignored) {}
        if(acquired) {
            synchronized(this) {  
                playerSubmittedSet = playerIdSubmitted;
                notifyAll();
            }

            // System.out.printf("Info: Thread %s submitted set.%n", Thread.currentThread().getName());
            // int[] setTokens = table.playerSetTokens(playerIdSubmitted);
            // boolean isValidSet = env.util.testSet(setTokens);
            // System.out.println(isValidSet);
            // if(isValidSet) {
            //     //remove the cards
            //     //reward player with a point + freeze
            //     //reset the timer
            // } else {
            //     //punish player
            // }
            // try {
            //     dealerThread.join();
            // } catch(InterruptedException e) {}
            lock.release();
        }
    }
}
