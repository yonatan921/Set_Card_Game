package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    public final long turnTimeout;

    public static final long SECOND = 1000;

    private Thread dealerThread;

    private final ArrayBlockingQueue<Integer> submissionQ;

    public final int SET_SIZE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        turnTimeout = env.config.turnTimeoutMillis;
        SET_SIZE = env.config.featureSize;
        reshuffleTime = System.currentTimeMillis() + turnTimeout;
        milliseconds = turnTimeout;
        submissionQ = new ArrayBlockingQueue<>(100000);
        
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
        terminate();
        announceWinners();
               
        System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
    
        }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    long lastSecond;
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime + 1500) { // set reshuffleTime = 60sec
            if(milliseconds != turnTimeout) {
                sleepUntilWokenOrTimeout();
            }
            if(milliseconds == turnTimeout || (System.currentTimeMillis() - lastSecond)/1000 == 1) //checks if 1 second passed, updates only if it did
                updateTimerDisplay(false);
            if(milliseconds < 0) {break;}
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
        Arrays.stream(players).forEach(Player::terminate);
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
        for (int j : cardsToRemove) {
            table.removeCardsFromTable(j, players);
        }

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Collections.shuffle(deck);
        Integer[] slotToCard = table.getSlotToCard();
        List<Integer> li = IntStream.rangeClosed(0, env.config.tableSize - 1).boxed().collect(Collectors.toList());
        Collections.shuffle(li);
        for(int slotNum = 0; slotNum < li.size() && deck.size() > 0; slotNum++) {
            if(slotToCard[li.get(slotNum)] == null) {
                Integer cardNum = deck.remove(deck.size() - 1);
                table.placeCard(cardNum, li.get(slotNum));
                try{
                    Thread.currentThread().sleep(env.config.tableDelayMillis);
                } catch(InterruptedException ignored) {}
            }
        }
        Arrays.stream(players).forEach(p -> p.setAllowedTokens(true));
        reshuffleTime = System.currentTimeMillis() + turnTimeout;
        lastSecond = System.currentTimeMillis(); 
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {
        try {
            wait(SECOND);
            handleSubmittedSet();
        } catch(InterruptedException ignored){} 
    }

    private void handleSubmittedSet() {
        while(submissionQ.size() != 0) {
            int playerSubmittedSet = submissionQ.remove();
            Integer[] setTokens = table.playerSetTokens(playerSubmittedSet);
            long numOfActualTokens = Arrays.stream(setTokens).filter(Objects::nonNull).count();
            Player player = players[playerSubmittedSet];
            if(numOfActualTokens == SET_SIZE) {
                int[] setTokensConverted = new int[SET_SIZE];
                for(int i = 0; i < SET_SIZE; i++) {
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
                player.handleFreeze(0);
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if((System.currentTimeMillis() - lastSecond)/SECOND == 1) {
            lastSecond = System.currentTimeMillis(); }
        if(reset || milliseconds < 0) {
            reshuffleTime = System.currentTimeMillis() + turnTimeout;
            milliseconds = turnTimeout;
        } else if(milliseconds >= 0){
            env.ui.setCountdown(milliseconds, false);
            milliseconds -= SECOND;
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        if(!terminate) {
            Arrays.stream(players).forEach(p -> p.setAllowedTokens(false));
            table.removeAllTokens();
            Arrays.stream(players).forEach(Player::removeAllTokens);
            List<Integer> li = IntStream.rangeClosed(0, env.config.tableSize - 1).boxed().collect(Collectors.toList());
            Collections.shuffle(li);
            for (Integer integer : li) {
                if (table.getCardInSlot(integer) != null) {
                    deck.add(table.getCardInSlot(integer));
                    table.removeCard(integer);
                    try {
                        Thread.currentThread().sleep(env.config.tableDelayMillis);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            submissionQ.clear();
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        Optional<Player> winner = Arrays.stream(players).max(Comparator.comparingInt(Player::score));
        if (winner.isPresent()){
            int score = winner.get().score();
            int [] winners = Arrays.stream(players).filter(p -> p.score() == score).mapToInt( x -> x.id).toArray();
            env.ui.announceWinner(winners);
        }
    }

    public void submitedSet(int playerIdSubmitted) {
        submissionQ.add(playerIdSubmitted);
        synchronized(this) {
            notifyAll();
        }
    }
}
