package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealerTest {

    private Dealer dealer;
    private Player[] players;

    @Mock
    Player player1;

    @Mock
    Player player2;
    @Mock
    private Logger logger;

    @Mock
    private UserInterface ui;

    @Mock
    private Table table;

    @Mock
    Util util;

    @BeforeEach
    void setUp() {
        Env env = new Env(logger, new Config(logger, ""), ui, util);
        players = new Player[]{player1, player2}; //magic number
        dealer = new Dealer(env, table, players);

    }

    @Test
    void announceWinners() {
        Random rnd = new Random();
        int score1 = rnd.nextInt();
        int score2 = rnd.nextInt();

        when(player1.score()).thenReturn(score1);
        when(player2.score()).thenReturn(score2);

        int expected = Math.max(score1, score2);

        assertEquals(dealer.announceWinners(), expected);

    }

    @Test
    void submitSet(){
        Random rnd = new Random();
        int player = rnd.nextInt(2);

        int takenBefore = dealer.getSubmissionQ().size();
        int expected = takenBefore + 1;

        dealer.submitedSet(player);

        assertEquals(dealer.getSubmissionQ().size(), expected);
    }
}