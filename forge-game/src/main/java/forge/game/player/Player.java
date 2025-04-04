/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.game.player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedSet;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.TreeBasedTable;

import forge.ImageKeys;
import forge.LobbyPlayer;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameEntity;
import forge.game.GameEntityCounterTable;
import forge.game.GameLogEntryType;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.DetachedCardEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardFactoryUtil;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardPredicates.Presets;
import forge.game.card.CardUtil;
import forge.game.card.CardZoneTable;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.event.GameEventCardSacrificed;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventManaBurn;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerControl;
import forge.game.event.GameEventPlayerCounters;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventPlayerPoisoned;
import forge.game.event.GameEventPlayerStatsChanged;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSurveil;
import forge.game.keyword.Companion;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordCollection;
import forge.game.keyword.KeywordCollection.KeywordCollectionView;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.KeywordsChange;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.replacement.ReplacementResult;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantBeCast;
import forge.game.staticability.StaticAbilityCantDiscard;
import forge.game.staticability.StaticAbilityCantBecomeMonarch;
import forge.game.staticability.StaticAbilityCantDraw;
import forge.game.staticability.StaticAbilityCantGainLosePayLife;
import forge.game.staticability.StaticAbilityCantPutCounter;
import forge.game.staticability.StaticAbilityCantTarget;
import forge.game.staticability.StaticAbilityCantSetSchemesInMotion;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.PlayerZone;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.util.Aggregates;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.TextUtil;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;

/**
 * <p>
 * Abstract Player class.
 * </p>
 *
 * @author Forge
 * @version $Id$
 */
public class Player extends GameEntity implements Comparable<Player> {
    public static final List<ZoneType> ALL_ZONES = Collections.unmodifiableList(Arrays.asList(ZoneType.Battlefield,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Hand, ZoneType.Exile, ZoneType.Command, ZoneType.Ante,
            ZoneType.Sideboard, ZoneType.PlanarDeck, ZoneType.SchemeDeck, ZoneType.Merged, ZoneType.Subgame, ZoneType.None));

    private final Map<Card, Integer> commanderDamage = Maps.newHashMap();

    private int life = 20;
    private int startingLife = 20;
    private int lifeStartedThisTurnWith = startingLife;
    private int spellsCastThisTurn;
    private int spellsCastThisGame;
    private int spellsCastLastTurn;
    private int landsPlayedThisTurn;
    private int landsPlayedLastTurn;
    private int investigatedThisTurn;
    private int surveilThisTurn;
    private int cycledThisTurn;
    private int equippedThisTurn;
    private int lifeLostThisTurn;
    private int lifeLostLastTurn;
    private int lifeGainedThisTurn;
    private int lifeGainedTimesThisTurn;
    private int lifeGainedByTeamThisTurn;
    private int numPowerSurgeLands;
    private int numLibrarySearchedOwn; //The number of times this player has searched his library
    private int numDrawnThisTurn;
    private int numDrawnThisDrawStep;
    private int numRollsThisTurn;
    private int numDiscardedThisTurn;
    private int numTokenCreatedThisTurn;
    private int numForetoldThisTurn;
    private int numCardsInHandStartedThisTurnWith;
    private int venturedThisTurn;
    private int maxHandSize = 7;
    private int startingHandSize = 7;
    private boolean unlimitedHandSize = false;
    private Card lastDrawnCard;
    private String namedCard = "";
    private String namedCard2 = "";

    private int simultaneousDamage = 0;

    private int lastTurnNr = 0;

    private final Map<String, FCollection<String>> notes = Maps.newHashMap();
    private final Map<String, Integer> notedNum = Maps.newHashMap();

    private boolean revolt = false;

    private List<Card> sacrificedThisTurn = new ArrayList<>();

    /** A list of tokens not in play, but on their way.
     * This list is kept in order to not break ETB-replacement
     * on tokens. */
    private CardCollection inboundTokens = new CardCollection();

    private KeywordCollection keywords = new KeywordCollection();
    // stores the keywords created by static abilities
    private final Table<Long, String, KeywordInterface> storedKeywords = TreeBasedTable.create();

    private Map<Card, DetachedCardEffect> staticAbilities = Maps.newHashMap();

    private Table<Long, Long, KeywordsChange> changedKeywords = TreeBasedTable.create();
    private ManaPool manaPool = new ManaPool(this);
    private Map<GameEntity, List<Card>> attackedThisTurn = new HashMap<>();
    private List<Player> attackedPlayersLastTurn = new ArrayList<>();
    private List<Player> attackedPlayersThisCombat = new ArrayList<>();

    private boolean activateLoyaltyAbilityThisTurn = false;
    private boolean tappedLandForManaThisTurn = false;
    private List<Card> completedDungeons = new ArrayList<>();

    private final Map<ZoneType, PlayerZone> zones = Maps.newEnumMap(ZoneType.class);
    private final Map<Long, Integer> adjustLandPlays = Maps.newHashMap();
    private final Set<Long> adjustLandPlaysInfinite = Sets.newHashSet();
    private Map<Card, Card> maingameCardsMap = Maps.newHashMap();

    private CardCollection currentPlanes = new CardCollection();

    private PlayerStatistics stats = new PlayerStatistics();
    private PlayerController controller;

    private NavigableMap<Long, Pair<Player, PlayerController>> controlledBy = Maps.newTreeMap();

    private NavigableMap<Long, Player> controlledWhileSearching = Maps.newTreeMap();

    private int teamNumber = -1;
    private Card activeScheme = null;
    private List<Card> commanders = Lists.newArrayList();
    private Map<Card, Integer> commanderCast = Maps.newHashMap();
    private final Game game;
    private boolean triedToDrawFromEmptyLibrary = false;
    private CardCollection lostOwnership = new CardCollection();
    private CardCollection gainedOwnership = new CardCollection();
    private int numManaConversion = 0;
    // The SA currently being paid for
    private Deque<SpellAbility> paidForStack = new ArrayDeque<>();

    private Card monarchEffect;
    private Card initiativeEffect;
    private Card blessingEffect;
    private Card keywordEffect;

    private Map<Long, Integer> additionalVotes = Maps.newHashMap();
    private Map<Long, Integer> additionalOptionalVotes = Maps.newHashMap();
    private SortedSet<Long> controlVotes = Sets.newTreeSet();

    private final AchievementTracker achievementTracker = new AchievementTracker();
    private final PlayerView view;

    public Player(String name0, Game game0, final int id0) {
        super(id0);

        game = game0;
        for (final ZoneType z : Player.ALL_ZONES) {
            final PlayerZone toPut = z == ZoneType.Battlefield ? new PlayerZoneBattlefield(z, this) : new PlayerZone(z, this);
            zones.put(z, toPut);
        }

        view = new PlayerView(id0, game.getTracker());
        view.updateMaxHandSize(this);
        view.updateKeywords(this);
        view.updateMaxLandPlay(this);
        setName(chooseName(name0));
        if (id0 >= 0) {
            game.addPlayer(id, this);
        }
    }

    public final AchievementTracker getAchievementTracker() {
        return achievementTracker;
    }

    public final PlayerOutcome getOutcome() {
        return stats.getOutcome();
    }

    private String chooseName(String originalName) {
        String nameCandidate = originalName;
        for (int i = 2; i <= 8; i++) { // several tries, not matter how many
            boolean haveDuplicates = false;
            for (Player p : game.getPlayers()) {
                if (p.getName().equals(nameCandidate)) {
                    haveDuplicates = true;
                    break;
                }
            }
            if (!haveDuplicates) {
                return nameCandidate;
            }
            nameCandidate = Lang.getInstance().getOrdinal(i) + " " + originalName;
        }
        return nameCandidate;
    }

    @Override
    public Game getGame() {
        return game;
    }

    public final PlayerStatistics getStats() {
        return stats;
    }

    public final int getTeam() {
        return teamNumber;
    }
    public final void setTeam(int iTeam) {
        teamNumber = iTeam;
    }

    public boolean isArchenemy() {
        return getZone(ZoneType.SchemeDeck).size() > 0; //Only the archenemy has schemes.
    }

    public Card getActiveScheme() {
        return activeScheme;
    }

    public void setSchemeInMotion() {
        if (StaticAbilityCantSetSchemesInMotion.any(getGame())) {
            return;
        }

        // Replacement effects
        if (game.getReplacementHandler().run(ReplacementType.SetInMotion, AbilityKey.mapFromAffected(this)) != ReplacementResult.NotReplaced) {
            return;
        }

        Map<AbilityKey, Object> moveParams = AbilityKey.newMap();
        moveParams.put(AbilityKey.LastStateBattlefield, game.getLastStateBattlefield());
        moveParams.put(AbilityKey.LastStateGraveyard, game.getLastStateGraveyard());

        game.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
        activeScheme = getZone(ZoneType.SchemeDeck).get(0);
        // gameAction moveTo ?
        game.getAction().moveTo(ZoneType.Command, activeScheme, null, moveParams);
        game.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);

        // Run triggers
        final Map<AbilityKey, Object> runParams = AbilityKey.newMap();
        runParams.put(AbilityKey.Scheme, activeScheme);
        game.getTriggerHandler().runTrigger(TriggerType.SetInMotion, runParams, false);
    }

    /**
     * returns all opponents.
     * Should keep player relations somewhere in the match structure
     */
    public final PlayerCollection getOpponents() {
        return game.getPlayers().filter(PlayerPredicates.isOpponentOf(this));
    }

    public final PlayerCollection getRegisteredOpponents() {
        return game.getRegisteredPlayers().filter(PlayerPredicates.isOpponentOf(this));
    }

    public void updateOpponentsForView() {
        view.updateOpponents(this);
    }

    public void updateFlashbackForView() {
        view.updateFlashbackForPlayer(this);
    }

    //get single opponent for player if only one, otherwise returns null
    //meant to be used after game ends for the sake of achievements
    public Player getSingleOpponent() {
        if (game.getRegisteredPlayers().size() == 2) {
            for (Player p : game.getRegisteredPlayers()) {
                if (p.isOpponentOf(this)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Find the smallest life total amongst this player's opponents.
     */
    public final int getOpponentsSmallestLifeTotal() {
        return Aggregates.min(getOpponents(), Accessors.FN_GET_LIFE_TOTAL);
    }

    /**
     * Find the greatest life total amongst this player's opponents.
     */
    public final int getOpponentsGreatestLifeTotal() {
        return Aggregates.max(getOpponents(), Accessors.FN_GET_LIFE_TOTAL);
    }

    /**
     * Get the total number of poison counters amongst this player's opponents.
     */
    public final int getOpponentsTotalPoisonCounters() {
        return Aggregates.sum(getOpponents(), Accessors.FN_GET_POISON_COUNTERS);
    }

    /**
     * returns allied players.
     * Should keep player relations somewhere in the match structure
     */
    public final PlayerCollection getAllies() {
        return getAllOtherPlayers().filter(Predicates.not(PlayerPredicates.isOpponentOf(this)));
    }

    public final PlayerCollection getTeamMates(final boolean inclThis) {
        PlayerCollection col = new PlayerCollection();
        if (inclThis) {
            col.add(this);
        }
        col.addAll(getAllOtherPlayers().filter(PlayerPredicates.sameTeam(this)));
        return col;
    }

    /**
     * returns allied players.
     * Should keep player relations somewhere in the match structure
     */
    public final PlayerCollection getYourTeam() {
        return getTeamMates(true);
    }

    /**
     * returns all other players.
     * Should keep player relations somewhere in the match structure
     */
    public final PlayerCollection getAllOtherPlayers() {
        PlayerCollection result = new PlayerCollection(game.getPlayers());
        result.remove(this);
        return result;
    }

    /**
     * returns the weakest opponent (based on life totals).
     * Should keep player relations somewhere in the match structure
     */
    public final Player getWeakestOpponent() {
        return getOpponents().min(PlayerPredicates.compareByLife());
    }
    public final Player getStrongestOpponent() {
        return getOpponents().max(PlayerPredicates.compareByLife());
    }

    public boolean isOpponentOf(Player other) {
        return other != this && other != null && (other.teamNumber < 0 || other.teamNumber != teamNumber);
    }
    public boolean isOpponentOf(String other) {
        Player otherPlayer = null;
        for (Player p : game.getPlayers()) {
            if (p.getName().equals(other)) {
                otherPlayer = p;
                break;
            }
        }
        return isOpponentOf(otherPlayer);
    }

    public final boolean setLife(final int newLife, final SpellAbility sa) {
        boolean change = false;
        // rule 119.5
        if (life > newLife) {
            change = loseLife(life - newLife, false, false) > 0;
        }
        else if (newLife > life) {
            change = gainLife(newLife - life, sa == null ? null : sa.getHostCard(), sa);
        }
        else { // life == newLife
            change = false;
        }
        return change;
    }

    public final int getStartingLife() {
        return startingLife;
    }
    public final void setStartingLife(final int startLife) {
        //Should only be called from newGame().
        startingLife = startLife;
        life = startLife;
        view.updateLife(this);
    }

    public final int getLife() {
        return life;
    }

    public final boolean gainLife(int lifeGain, final Card source, final SpellAbility sa) {
        if (!canGainLife()) {
            return false;
        }

        // Run any applicable replacement effects.
        final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
        repParams.put(AbilityKey.LifeGained, lifeGain);
        repParams.put(AbilityKey.SourceSA, sa);

        switch (getGame().getReplacementHandler().run(ReplacementType.GainLife, repParams)) {
        case NotReplaced:
            break;
        case Updated:
            // check if this is still the affected player
            if (this.equals(repParams.get(AbilityKey.Affected))) {
                lifeGain = (int) repParams.get(AbilityKey.LifeGained);
                // there is nothing that changes lifegain into lifeloss this way
                if (lifeGain <= 0) {
                    return false;
                }
            } else {
                return false;
            }
            break;
        default:
            return false;
        }

        boolean newLifeSet = false;

        if (lifeGain > 0) {
            int oldLife = life;
            life += lifeGain;
            view.updateLife(this);
            newLifeSet = true;
            lifeGainedThisTurn += lifeGain;
            lifeGainedTimesThisTurn++;

            // team mates need to be notified about life gained
            for (final Player p : getTeamMates(true)) {
                p.addLifeGainedByTeamThisTurn(lifeGain);
            }

            // Run triggers
            final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
            runParams.put(AbilityKey.LifeAmount, lifeGain);
            runParams.put(AbilityKey.Source, source);
            runParams.put(AbilityKey.SourceSA, sa);
            game.getTriggerHandler().runTrigger(TriggerType.LifeGained, runParams, false);

            game.fireEvent(new GameEventPlayerLivesChanged(this, oldLife, life));
        } else {
            System.out.println("Player - trying to gain negative or 0 life");
        }
        return newLifeSet;
    }

    public final boolean canGainLife() {
        return isInGame() && !StaticAbilityCantGainLosePayLife.anyCantGainLife(this);
    }

    public final int loseLife(int toLose, final boolean damage, final boolean manaBurn) {
        int lifeLost = 0;
        if (!canLoseLife()) {
            return 0;
        }
        if (toLose > 0) {
            int oldLife = life;
            // Run applicable replacement effects
            final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
            repParams.put(AbilityKey.Result, oldLife-toLose);
            repParams.put(AbilityKey.IsDamage, damage);

            switch (getGame().getReplacementHandler().run(ReplacementType.LifeReduced, repParams)) {
            case NotReplaced:
                break;
            case Updated:
                // check if this is still the affected player
                if (this.equals(repParams.get(AbilityKey.Affected))) {
                    int result = (int) repParams.get(AbilityKey.Result);
                    toLose = oldLife - result;
                    // there is nothing that changes lifegain into lifeloss this way
                    if (toLose <= 0) {
                        return 0;
                    }
                } else {
                    return 0;
                }
                break;
            default:
                return 0;
            }

            life -= toLose;
            view.updateLife(this);
            lifeLost = toLose;
            if (manaBurn) {
                game.fireEvent(new GameEventManaBurn(this, lifeLost, true));
            } else {
                game.fireEvent(new GameEventPlayerLivesChanged(this, oldLife, life));
            }
        } else if (toLose == 0) {
            // Rule 118.4
            // this is for players being able to pay 0 life nothing to do
            // no trigger for lost no life
            return 0;
        } else {
            System.out.println("Player - trying to lose negative life");
            return 0;
        }

        boolean firstLost = lifeLostThisTurn == 0;

        lifeLostThisTurn += toLose;

        // Run triggers
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.LifeAmount, toLose);
        runParams.put(AbilityKey.FirstTime, firstLost);
        game.getTriggerHandler().runTrigger(TriggerType.LifeLost, runParams, false);

        return lifeLost;
    }

    public final boolean canLoseLife() {
        return isInGame() && !StaticAbilityCantGainLosePayLife.anyCantLoseLife(this);
    }

    public final boolean canPayLife(final int lifePayment, final boolean effect, SpellAbility cause) {
        if (lifePayment > 0 && life < lifePayment) {
            return false;
        }
        return lifePayment <= 0 || !StaticAbilityCantGainLosePayLife.anyCantPayLife(this, effect, cause);
    }

    public final boolean payLife(final int lifePayment, final SpellAbility cause, final boolean effect) {
        if (!canPayLife(lifePayment, effect, cause)) {
            return false;
        }

        loseLife(lifePayment, false, false);
        cause.setPaidLife(lifePayment);

        // Run triggers
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.LifeAmount, lifePayment);
        game.getTriggerHandler().runTrigger(TriggerType.PayLife, runParams, false);

        return true;
    }

    public final boolean canPayEnergy(final int energyPayment) {
        int cnt = getCounters(CounterEnumType.ENERGY);
        return cnt >= energyPayment;
    }

    public final int loseEnergy(int lostEnergy) {
        int cnt = getCounters(CounterEnumType.ENERGY);
        if (lostEnergy > cnt) {
            return -1;
        }
        cnt -= lostEnergy;
        this.setCounters(CounterEnumType.ENERGY, cnt, true);
        return cnt;
    }

    public final boolean payEnergy(final int energyPayment, final Card source) {
        if (energyPayment <= 0)
            return true;

        return canPayEnergy(energyPayment) && loseEnergy(energyPayment) > -1;
    }

    // This function handles damage after replacement and prevention effects are applied
    @Override
    public final int addDamageAfterPrevention(final int amount, final Card source, final boolean isCombat, GameEntityCounterTable counterTable) {
        if (amount <= 0 || hasLost()) {
            return 0;
        }

        boolean infect = source.hasKeyword(Keyword.INFECT)
                || hasKeyword("All damage is dealt to you as though its source had infect.");

        int poisonCounters = 0;
        if (infect) {
            poisonCounters += amount;
        }
        else if (!hasKeyword("Damage doesn't cause you to lose life.")) {
            // rule 118.2. Damage dealt to a player normally causes that player to lose that much life.
            if (isCombat) {
                // currently all abilities treat is as single event
                simultaneousDamage += amount;
            } else {
                loseLife(amount, true, false);
            }
        }

        if (isCombat) {
            poisonCounters += source.getKeywordMagnitude(Keyword.TOXIC);
        }

        if (poisonCounters > 0) {
            addPoisonCounters(poisonCounters, source.getController(), counterTable);
        }

        //Oathbreaker, Tiny Leaders, and Brawl ignore commander damage rule
        if (source.isCommander() && isCombat
                && !this.getGame().getRules().hasAppliedVariant(GameType.Oathbreaker)
                && !this.getGame().getRules().hasAppliedVariant(GameType.TinyLeaders)
                && !this.getGame().getRules().hasAppliedVariant(GameType.Brawl)) {
            // In case that commander is merged permanent, get the real commander card
            final Card realCommander = source.getRealCommander();
            addCommanderDamage(realCommander, amount);
            view.updateCommanderDamage(this);
            if (realCommander != source) {
                view.updateMergedCommanderDamage(source, realCommander);
            }
        }

        // Run triggers
        final Map<AbilityKey, Object> runParams = AbilityKey.newMap();
        runParams.put(AbilityKey.DamageSource, source);
        runParams.put(AbilityKey.DamageTarget, this);
        runParams.put(AbilityKey.DamageAmount, amount);
        runParams.put(AbilityKey.IsCombatDamage, isCombat);
        // Defending player at the time the damage was dealt
        runParams.put(AbilityKey.DefendingPlayer, game.getCombat() != null ? game.getCombat().getDefendingPlayerRelatedTo(source) : null);
        game.getTriggerHandler().runTrigger(TriggerType.DamageDone, runParams, isCombat);

        game.fireEvent(new GameEventPlayerDamaged(this, source, amount, isCombat, infect));

        return amount;
    }

    // This is usable by the AI to forecast an effect (so it must
    // not change the game state)
    // 2012/01/02: No longer used in calculating the finalized damage, but
    // retained for damageprediction. -Hellfish
    @Override
    public final int staticReplaceDamage(final int damage, final Card source, final boolean isCombat) {
        int restDamage = damage;

        // TODO handle life loss replacement

        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Sulfuric Vapors")) {
                if (source.isSpell() && source.isRed()) {
                    restDamage += 1;
                }
            } else if (c.getName().equals("Pyromancer's Swath")) {
                if (c.getController().equals(source.getController()) && (source.isInstant() || source.isSorcery())) {
                    restDamage += 2;
                }
            } else if (c.getName().equals("Pyromancer's Gauntlet")) {
                if (c.getController().equals(source.getController()) && source.isRed()
                        && (source.isInstant() || source.isSorcery() || source.isPlaneswalker())) {
                    restDamage += 2;
                }
            } else if (c.getName().equals("Furnace of Rath") || c.getName().equals("Dictate of the Twin Gods")) {
                restDamage *= 2;
            } else if (c.getName().equals("Gratuitous Violence")) {
                if (c.getController().equals(source.getController()) && source.isCreature()) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Fire Servant")) {
                if (c.getController().equals(source.getController()) && source.isRed()
                        && (source.isInstant() || source.isSorcery())) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Curse of Bloodletting")) {
                if (c.getEntityAttachedTo().equals(this)) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Gisela, Blade of Goldnight")) {
                if (!c.getController().equals(this)) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Inquisitor's Flail")) {
                if (isCombat && c.getEquipping() != null && c.getEquipping().equals(source)) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Ghosts of the Innocent")) {
                restDamage = restDamage / 2;
            } else if (c.getName().equals("Benevolent Unicorn")) {
                if (source.isSpell()) {
                    restDamage -= 1;
                }
            } else if (c.getName().equals("Divine Presence")) {
                if (restDamage > 3) {
                    restDamage = 3;
                }
            } else if (c.getName().equals("Forethought Amulet")) {
                if (c.getController().equals(this) && (source.isInstant() || source.isSorcery())
                        && restDamage > 2) {
                    restDamage = 2;
                }
            } else if (c.getName().equals("Elderscale Wurm")) {
                if (c.getController().equals(this) && getLife() - restDamage < 7) {
                    restDamage = getLife() - 7;
                    if (restDamage < 0) {
                        restDamage = 0;
                    }
                }
            } else if (c.getName().equals("Obosh, the Preypiercer")) {
                if (c.getController().equals(source.getController()) && source.getCMC() % 2 != 0) {
                    restDamage *= 2;
                }
            }
        }

        // TODO: improve such that this can be predicted from the replacement effect itself
        // (+ move this function out into ComputerUtilCombat?)
        for (Card c : game.getCardsIn(ZoneType.Command)) {
            if (c.getName().equals("Insult Effect")) {
                if (c.getController().equals(source.getController())) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Mishra")) {
                if (c.isCreature() && c.getController().equals(source.getController())) {
                    restDamage *= 2;
                }
            }
        }

        return restDamage;
    }

    public final void dealCombatDamage() {
        loseLife(simultaneousDamage, true, false);
        simultaneousDamage = 0;
    }

    /**
     * Get the total damage assigned to this player's opponents this turn.
     */
    public final int getOpponentsAssignedDamage() {
        return Aggregates.sum(getOpponents(), Accessors.FN_GET_ASSIGNED_DAMAGE);
    }

    /**
     * Get the greatest amount of damage assigned to a single opponent this turn.
     */
    public final int getMaxOpponentAssignedDamage() {
        return Aggregates.max(getOpponents(), Accessors.FN_GET_ASSIGNED_DAMAGE);
    }

    public final boolean canReceiveCounters(final CounterType type) {
        if (!isInGame()) {
            return false;
        }
        if (StaticAbilityCantPutCounter.anyCantPutCounter(this, type)) {
            return false;
        }
        return true;
    }

    @Override
    public void addCounterInternal(final CounterType counterType, final int n, final Player source, final boolean fireEvents, GameEntityCounterTable table, Map<AbilityKey, Object> params) {
        int addAmount = n;
        if (addAmount <= 0 || !canReceiveCounters(counterType)) {
            // As per rule 107.1b
            return;
        }

        final int oldValue = getCounters(counterType);
        final int newValue = addAmount + oldValue;
        this.setCounters(counterType, newValue, fireEvents);

        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.Source, source);
        runParams.put(AbilityKey.CounterType, counterType);
        if (params != null) {
            runParams.putAll(params);
        }
        for (int i = 0; i < addAmount; i++) {
            runParams.put(AbilityKey.CounterAmount, oldValue + i + 1);
            getGame().getTriggerHandler().runTrigger(TriggerType.CounterAdded, AbilityKey.newMap(runParams), false);
        }
        if (addAmount > 0) {
            runParams.put(AbilityKey.CounterAmount, addAmount);
            getGame().getTriggerHandler().runTrigger(TriggerType.CounterAddedOnce, AbilityKey.newMap(runParams), false);
        }
        if (table != null) {
            table.put(source, this, counterType, addAmount);
        }
    }

    @Override
    public void subtractCounter(CounterType counterName, int num) {
        int oldValue = getCounters(counterName);
        int newValue = Math.max(oldValue - num, 0);

        final int delta = oldValue - newValue;
        if (delta == 0) { return; }

        setCounters(counterName, newValue, true);

        /* TODO Run triggers when something cares
        int curCounters = oldValue;
        for (int i = 0; i < delta && curCounters != 0; i++) {
            final Map<String, Object> runParams = new TreeMap<>();
            runParams.put("Card", this);
            runParams.put("CounterType", counterName);
            runParams.put("NewCounterAmount", --curCounters);
            getGame().getTriggerHandler().runTrigger(TriggerType.CounterRemoved, runParams, false);
        }
        */
    }

    public final void clearCounters() {
        if (counters.isEmpty()) { return; }
        counters.clear();
        view.updateCounters(this);
        getGame().fireEvent(new GameEventPlayerCounters(this, null, 0, 0));
    }

    public void setCounters(final CounterEnumType counterType, final Integer num, boolean fireEvents) {
        this.setCounters(CounterType.get(counterType), num, fireEvents);
    }

    public void setCounters(final CounterType counterType, final Integer num, boolean fireEvents) {
        Integer old = getCounters(counterType);
        setCounters(counterType, num);
        view.updateCounters(this);
        if (fireEvents) {
            getGame().fireEvent(new GameEventPlayerCounters(this, counterType, old, num));
        }
    }

    @Override
    public void setCounters(Map<CounterType, Integer> allCounters) {
        counters = allCounters;
        view.updateCounters(this);
        getGame().fireEvent(new GameEventPlayerCounters(this, null, 0, 0));
    }

    // TODO Merge These calls into the primary counter calls
    public final int getPoisonCounters() {
        return getCounters(CounterEnumType.POISON);
    }
    public final void setPoisonCounters(final int num, Player source) {
        int oldPoison = getCounters(CounterEnumType.POISON);
        setCounters(CounterEnumType.POISON, num, true);
        game.fireEvent(new GameEventPlayerPoisoned(this, source, oldPoison, num));
    }
    public final void addPoisonCounters(final int num, final Player source, GameEntityCounterTable table) {
        int oldPoison = getCounters(CounterEnumType.POISON);
        addCounter(CounterEnumType.POISON, num, source, table);

        if (oldPoison != getCounters(CounterEnumType.POISON)) {
            game.fireEvent(new GameEventPlayerPoisoned(this, source, oldPoison, num));
        }
    }
    public final void removePoisonCounters(final int num, final Player source) {
        int oldPoison = getCounters(CounterEnumType.POISON);
        subtractCounter(CounterEnumType.POISON, num);

        if (oldPoison != getCounters(CounterEnumType.POISON)) {
            game.fireEvent(new GameEventPlayerPoisoned(this, source, oldPoison, num));
        }
    }
    // ================ POISON Merged =================================
    public final void addChangedKeywords(final List<String> addKeywords, final List<String> removeKeywords, final Long timestamp, final long staticId) {
        List<KeywordInterface> kws = Lists.newArrayList();
        if (addKeywords != null) {
            for (String kw : addKeywords) {
                kws.add(getKeywordForStaticAbility(kw, staticId));
            }
        }
        KeywordsChange cks = new KeywordsChange(kws, removeKeywords, false);
        if (!cks.getAbilities().isEmpty() || !cks.getTriggers().isEmpty() || !cks.getReplacements().isEmpty() || !cks.getStaticAbilities().isEmpty()) {
            getKeywordCard().addChangedCardTraits(
                cks.getAbilities(), null, cks.getTriggers(), cks.getReplacements(), cks.getStaticAbilities(), false, false, timestamp, staticId);
        }
        changedKeywords.put(timestamp, staticId, cks);
        updateKeywords();
        game.fireEvent(new GameEventPlayerStatsChanged(this, true));
    }

    public final KeywordInterface getKeywordForStaticAbility(String kw, final long staticId) {
        KeywordInterface result;
        if (staticId < 1 || !storedKeywords.contains(staticId, kw)) {
            result = Keyword.getInstance(kw);
            result.createTraits(this, false);
        } else {
            result = storedKeywords.get(staticId, kw);
        }
        return result;
    }

    public final KeywordsChange removeChangedKeywords(final Long timestamp, final long staticId) {
        KeywordsChange change = changedKeywords.remove(timestamp, staticId);
        if (change != null) {
            if (keywordEffect != null) {
                getKeywordCard().removeChangedCardTraits(timestamp, staticId);
            }
            updateKeywords();
            game.fireEvent(new GameEventPlayerStatsChanged(this, true));
        }
        return change;
    }

    /**
     * Append a keyword change which adds the specified keyword.
     * @param keyword the keyword to add.
     */
    public final void addKeyword(final String keyword) {
        addChangedKeywords(ImmutableList.of(keyword), ImmutableList.of(), getGame().getNextTimestamp(), 0);
    }

    @Override
    public final boolean hasKeyword(final String keyword) {
        return keywords.contains(keyword);
    }
    @Override
    public final boolean hasKeyword(final Keyword keyword) {
        return keywords.contains(keyword);
    }

    private void updateKeywords() {
        keywords.clear();

        // see if keyword changes are in effect
        for (final KeywordsChange ck : changedKeywords.values()) {
            if (ck.isRemoveAllKeywords()) {
                keywords.clear();
            }
            else if (ck.getRemoveKeywords() != null) {
                keywords.removeAll(ck.getRemoveKeywords());
            }

            if (ck.getKeywords() != null) {
                keywords.insertAll(ck.getKeywords());
            }
        }
        view.updateKeywords(this);
        updateKeywordCardAbilityText();
    }

    public final KeywordCollectionView getKeywords() {
        return keywords.getView();
    }

    public final FCollectionView<StaticAbility> getStaticAbilities() {
        FCollection<StaticAbility> result = new FCollection<>();
        for (DetachedCardEffect eff : staticAbilities.values()) {
            result.addAll(eff.getStaticAbilities());
        }
        return result;
    }

    public final StaticAbility addStaticAbility(final Card host, final String s) {
        PlayerZone com = getZone(ZoneType.Command);

        if (!staticAbilities.containsKey(host)) {
            DetachedCardEffect effect = new DetachedCardEffect(host, host.getName() + "'s Effect");
            effect.setOwner(this);
            staticAbilities.put(host, effect);
        }

        if (!com.contains(staticAbilities.get(host))) {
            com.add(staticAbilities.get(host));
            this.updateZoneForView(com);
        }

        return staticAbilities.get(host).addStaticAbility(s);
    }

    public final void clearStaticAbilities() {
        PlayerZone com = getZone(ZoneType.Command);
        for (DetachedCardEffect eff : staticAbilities.values()) {
            com.remove(eff);
            eff.setStaticAbilities(Lists.newArrayList());
        }
        this.updateZoneForView(com);
    }

    @Override
    public final boolean canBeTargetedBy(final SpellAbility sa) {
        if (hasLost()) {
            return false;
        }

        // CantTarget static abilities
        if (StaticAbilityCantTarget.cantTarget(this, sa)) {
            return false;
        }

        return true;
    }

    public void surveil(int num, SpellAbility cause, CardZoneTable table, Map<AbilityKey, Object> params) {
        final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
        repParams.put(AbilityKey.Source, cause);
        repParams.put(AbilityKey.SurveilNum, num);
        if (params != null) {
            repParams.putAll(params);
        }

        switch (getGame().getReplacementHandler().run(ReplacementType.Surveil, repParams)) {
            case NotReplaced:
                break;
            case Updated: {
                num = (int) repParams.get(AbilityKey.SurveilNum);
                break;
            }
            default:
                return;
        }

        final CardCollection topN = new CardCollection(this.getCardsIn(ZoneType.Library, num));

        if (topN.isEmpty()) {
            return;
        }

        final ImmutablePair<CardCollection, CardCollection> lists = getController().arrangeForSurveil(topN);
        final CardCollection toTop = lists.getLeft();
        final CardCollection toGrave = lists.getRight();

        int numToGrave = 0;
        int numToTop = 0;

        if (toGrave != null) {
            for (Card c : toGrave) {
                ZoneType oZone = c.getZone().getZoneType();
                Card moved = getGame().getAction().moveToGraveyard(c, cause, params);
                table.put(oZone, moved.getZone().getZoneType(), moved);
                numToGrave++;
            }
        }

        if (toTop != null) {
            Collections.reverse(toTop); // the last card in list will become topmost in library, have to revert thus.
            for (Card c : toTop) {
                getGame().getAction().moveToLibrary(c, cause, params);
                numToTop++;
            }
        }

        getGame().fireEvent(new GameEventSurveil(this, numToTop, numToGrave));

        surveilThisTurn++;
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.NumThisTurn, surveilThisTurn);
        if (params != null) {
            runParams.putAll(params);
        }
        getGame().getTriggerHandler().runTrigger(TriggerType.Surveil, runParams, false);
    }

    public int getSurveilThisTurn() {
        return surveilThisTurn;
    }

    public void resetSurveilThisTurn() {
        surveilThisTurn = 0;
    }

    public boolean canMulligan() {
        return !getZone(ZoneType.Hand).isEmpty();
    }

    public final boolean canDraw() {
        return canDrawAmount(1);
    }

    public final boolean canDrawAmount(int amount) {
        return StaticAbilityCantDraw.canDrawThisAmount(this, amount);
    }

    public final CardCollectionView drawCard() {
        return drawCards(1, null, AbilityKey.newMap());
    }

    public final CardCollectionView drawCards(final int n) {
        return drawCards(n, null, AbilityKey.newMap());
    }
    public final CardCollectionView drawCards(final int n, SpellAbility cause, Map<AbilityKey, Object> params) {
        final CardCollection drawn = new CardCollection();
        if (n <= 0) {
            return drawn;
        }

        // Replacement effects
        final Map<AbilityKey, Object> repRunParams = AbilityKey.mapFromAffected(this);
        repRunParams.put(AbilityKey.Number, n);
        if (params != null) {
            repRunParams.putAll(params);
        }

        if (game.getReplacementHandler().run(ReplacementType.DrawCards, repRunParams) != ReplacementResult.NotReplaced) {
            return drawn;
        }

        // always allow drawing cards before the game actually starts (e.g. Maralen of the Mornsong Avatar)
        final boolean gameStarted = game.getAge().ordinal() > GameStage.Mulligan.ordinal();
        final Map<Player, CardCollection> toReveal = Maps.newHashMap();

        for (int i = 0; i < n; i++) {
            if (gameStarted && !canDraw()) {
                return drawn;
            }
            drawn.addAll(doDraw(toReveal, cause, params));
        }

        // reveal multiple drawn cards when playing with the top of the library revealed
        for (Map.Entry<Player, CardCollection> e : toReveal.entrySet()) {
            if (e.getValue().size() > 1) {
                game.getAction().revealTo(e.getValue(), e.getKey(), "Revealing cards drawn from ");
            }
        }
        return drawn;
    }

    /**
     * @return a CardCollectionView of cards actually drawn
     */
    private CardCollectionView doDraw(Map<Player, CardCollection> revealed, SpellAbility cause, Map<AbilityKey, Object> params) {
        final CardCollection drawn = new CardCollection();
        final PlayerZone library = getZone(ZoneType.Library);

        // Replacement effects
        Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
        repParams.put(AbilityKey.Cause, cause);
        if (params != null) {
            repParams.putAll(params);
        }
        if (game.getReplacementHandler().run(ReplacementType.Draw, repParams) != ReplacementResult.NotReplaced) {
            return drawn;
        }

        if (!library.isEmpty()) {
            Card c;

            if (hasKeyword("You draw cards from the bottom of your library instead of the top of your library.")) {
                c = library.get(library.size() - 1);
            } else {
                c = library.get(0);
            }

            List<Player> pList = Lists.newArrayList();
            for (Player p : getAllOtherPlayers()) {
                if (c.mayPlayerLook(p)) {
                    pList.add(p);
                }
            }

            c = game.getAction().moveToHand(c, cause, params);
            drawn.add(c);

            for (Player p : pList) {
                if (!revealed.containsKey(p)) {
                    revealed.put(p, new CardCollection());
                }
                revealed.get(p).add(c);
            }

            final boolean gameStarted = game.getAge().ordinal() > GameStage.Mulligan.ordinal();
            if (gameStarted) {
                setLastDrawnCard(c);
                c.setDrawnThisTurn(true);
                numDrawnThisTurn++;
                if (game.getPhaseHandler().is(PhaseType.DRAW)) {
                    numDrawnThisDrawStep++;
                }
                view.updateNumDrawnThisTurn(this);

                final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
                if (params != null) {
                    runParams.putAll(params);
                }

                // CR 121.8 card was drawn as part of another sa (e.g. paying with Chromantic Sphere), hide it temporarily
                if (game.getTopLibForPlayer(this) != null && getPaidForSA() != null && cause != null && getPaidForSA() != cause.getRootAbility()) {
                    c.turnFaceDown();
                    game.addFacedownWhileCasting(c, numDrawnThisTurn);
                    runParams.put(AbilityKey.CanReveal, false);
                }

                // Run triggers
                runParams.put(AbilityKey.Card, c);
                runParams.put(AbilityKey.Number, numDrawnThisTurn);
                game.getTriggerHandler().runTrigger(TriggerType.Drawn, runParams, false);
            }
        }
        else { // Lose by milling is always on. Give AI many cards it cannot play if you want it not to undertake actions
            triedToDrawFromEmptyLibrary = true;
        }
        return drawn;
    }

    /**
     * Returns PlayerZone corresponding to the given zone of game.
     */
    public final PlayerZone getZone(final ZoneType zone) {
        return zones.get(zone);
    }
    public void updateZoneForView(PlayerZone zone) {
        view.updateZone(zone);
    }

    public final CardCollectionView getCardsIn(final ZoneType zoneType) {
        return getCardsIn(zoneType, true);
    }

    /**
     * gets a list of all cards in the requested zone. This function makes a CardCollectionView from Card[].
     */
    public final CardCollectionView getCardsIn(final ZoneType zoneType, boolean filterOutPhasedOut) {
        if (zoneType == ZoneType.Stack) {
            CardCollection cards = new CardCollection();
            for (Card c : game.getStackZone().getCards()) {
                if (c.getOwner().equals(this)) {
                    cards.add(c);
                }
            }
            return cards;
        }
        else if (zoneType == ZoneType.Flashback) {
            return getCardsActivableInExternalZones(true);
        }

        PlayerZone zone = getZone(zoneType);
        return zone == null ? CardCollection.EMPTY : zone.getCards(filterOutPhasedOut);
    }

    /**
     * gets a list of first N cards in the requested zone. This function makes a CardCollectionView from Card[].
     */
    public final CardCollectionView getCardsIn(final ZoneType zone, final int n) {
        return new CardCollection(Iterables.limit(getCardsIn(zone), n));
    }

    /**
     * gets a list of all cards in a given player's requested zones.
     */
    public final CardCollectionView getCardsIn(final Iterable<ZoneType> zones) {
        return getCardsIn(zones, true);
    }
    public final CardCollectionView getCardsIn(final Iterable<ZoneType> zones, boolean filterOutPhasedOut) {
        final CardCollection result = new CardCollection();
        for (final ZoneType z : zones) {
            result.addAll(getCardsIn(z, filterOutPhasedOut));
        }
        return result;
    }
    public final CardCollectionView getCardsIn(final ZoneType[] zones) {
        final CardCollection result = new CardCollection();
        for (final ZoneType z : zones) {
            result.addAll(getCardsIn(z));
        }
        return result;
    }

    /**
     * gets a list of all cards with requested cardName in a given player's
     * requested zone. This function makes a CardCollectionView from Card[].
     */
    public final CardCollectionView getCardsIn(final ZoneType zone, final String cardName) {
        return CardLists.filter(getCardsIn(zone), CardPredicates.nameEquals(cardName));
    }

    public CardCollectionView getCardsActivableInExternalZones(boolean includeCommandZone) {
        final CardCollection cl = new CardCollection();

        cl.addAll(getZone(ZoneType.Graveyard).getCardsPlayerCanActivate(this));
        cl.addAll(getZone(ZoneType.Exile).getCardsPlayerCanActivate(this));
        cl.addAll(getZone(ZoneType.Library).getCardsPlayerCanActivate(this));
        if (includeCommandZone) {
            cl.addAll(getZone(ZoneType.Command).getCardsPlayerCanActivate(this));
            cl.addAll(getZone(ZoneType.Sideboard).getCardsPlayerCanActivate(this));
        }

        //External activatables from all opponents
        for (final Player other : getAllOtherPlayers()) {
            cl.addAll(other.getZone(ZoneType.Exile).getCardsPlayerCanActivate(this));
            cl.addAll(other.getZone(ZoneType.Graveyard).getCardsPlayerCanActivate(this));
            cl.addAll(other.getZone(ZoneType.Library).getCardsPlayerCanActivate(this));
            cl.addAll(other.getZone(ZoneType.Hand).getCardsPlayerCanActivate(this));
        }
        cl.addAll(getGame().getCardsPlayerCanActivateInStack());
        return cl;
    }

    public final CardCollectionView getAllCards() {
        return CardCollection.combine(getCardsIn(Player.ALL_ZONES), getCardsIn(ZoneType.Stack), inboundTokens);
    }

    public final void resetNumDrawnThisDrawStep() {
        numDrawnThisDrawStep = 0;
    }

    public final void resetNumDrawnThisTurn() {
        numDrawnThisTurn = 0;
        view.updateNumDrawnThisTurn(this);
    }

    public final int getNumDrawnThisTurn() {
        return numDrawnThisTurn;
    }

    public final int numDrawnThisDrawStep() {
        return numDrawnThisDrawStep;
    }

    public final void resetNumRollsThisTurn() {
        numRollsThisTurn = 0;
    }

    public final int getNumRollsThisTurn() {
        return numRollsThisTurn;
    }

    public void roll() {
        numRollsThisTurn++;
    }

    public final Card discard(final Card c, final SpellAbility sa, final boolean effect, CardZoneTable table, Map<AbilityKey, Object> params) {
        if (!c.canBeDiscardedBy(sa, effect)) {
            return null;
        }

        // TODO: This line should be moved inside CostPayment somehow
        /*if (sa != null) {
            sa.addCostToHashList(c, "Discarded");
        }*/
        final Card source = sa != null ? sa.getHostCard() : null;
        final ZoneType origin = c.getZone().getZoneType();

        boolean discardToTopOfLibrary = null != sa && sa.hasParam("DiscardToTopOfLibrary");
        boolean discardMadness = sa != null && sa.hasParam("Madness");

        // DiscardToTopOfLibrary and Madness are replacement discards,
        // that should not trigger other Replacement again
        if (!discardToTopOfLibrary && !discardMadness) {
            // Replacement effects
            final Map<AbilityKey, Object> repRunParams = AbilityKey.mapFromCard(c);
            repRunParams.put(AbilityKey.Source, source);
            repRunParams.put(AbilityKey.Affected, this);
            if (params != null) {
                repRunParams.putAll(params);
            }

            if (game.getReplacementHandler().run(ReplacementType.Discard, repRunParams) != ReplacementResult.NotReplaced) {
                return null;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(this).append(" discards ").append(c);
        final Card newCard;
        if (discardToTopOfLibrary) {
            newCard = game.getAction().moveToLibrary(c, 0, sa, params);
            sb.append(" to the library");
            // Play the Discard sound
        }
        else if (discardMadness) {
            newCard = game.getAction().exile(c, sa, params);
            sb.append(" with Madness");
        }
        else {
            newCard = game.getAction().moveToGraveyard(c, sa, params);
            // Play the Discard sound
        }

        newCard.setDiscarded(true);

        if (table != null) {
            table.put(origin, newCard.getZone().getZoneType(), newCard);
        }
        sb.append(".");
        numDiscardedThisTurn++;
        // Run triggers
        Card cause = null;
        if (sa != null) {
            cause = sa.getHostCard();
            // for Replacement of the discard Cause
            if (sa.hasParam("Cause")) {
                final CardCollection col = AbilityUtils.getDefinedCards(cause, sa.getParam("Cause"), sa);
                if (!col.isEmpty()) {
                    cause = col.getFirst();
                }
            }
        }
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.Card, c);
        runParams.put(AbilityKey.Cause, cause);
        runParams.put(AbilityKey.IsMadness, discardMadness);
        if (params != null) {
            runParams.putAll(params);
        }
        game.getTriggerHandler().runTrigger(TriggerType.Discarded, runParams, false);
        game.getGameLog().add(GameLogEntryType.DISCARD, sb.toString());
        return newCard;
    }

    public final void addTokensCreatedThisTurn(Card token) {
        numTokenCreatedThisTurn++;
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.Num, numTokenCreatedThisTurn);
        runParams.put(AbilityKey.Card, token);
        game.getTriggerHandler().runTrigger(TriggerType.TokenCreated, runParams, false);
    }

    public final void resetNumTokenCreatedThisTurn() {
        numTokenCreatedThisTurn = 0;
    }

    public final int getNumForetoldThisTurn() {
        return numForetoldThisTurn;
    }

    public final void addForetoldThisTurn() {
        numForetoldThisTurn++;
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.Num, numForetoldThisTurn);
        game.getTriggerHandler().runTrigger(TriggerType.Foretell, runParams, false);
    }

    public final void resetNumForetoldThisTurn() {
        numForetoldThisTurn = 0;
    }

    public final int getNumDiscardedThisTurn() {
        return numDiscardedThisTurn;
    }

    public final void resetNumDiscardedThisTurn() {
        numDiscardedThisTurn = 0;
    }

    public int getNumCardsInHandStartedThisTurnWith() {
        return numCardsInHandStartedThisTurnWith;
    }
    public void setNumCardsInHandStartedThisTurnWith(int num) {
        numCardsInHandStartedThisTurnWith = num;
    }

    public int getLifeStartedThisTurnWith() {
        return lifeStartedThisTurnWith;
    }
    public void setLifeStartedThisTurnWith(int l) {
        lifeStartedThisTurnWith = l;
    }

    public void addNoteForName(String notedFor, String noted) {
        if (!notes.containsKey(notedFor)) {
            notes.put(notedFor, new FCollection<>());
        }
        notes.get(notedFor).add(noted);
    }
    public FCollection<String> getNotesForName(String notedFor) {
        if (!notes.containsKey(notedFor)) {
            notes.put(notedFor, new FCollection<>());
        }
        return notes.get(notedFor);
    }
    public void clearNotesForName(String notedFor) {
        if (notes.containsKey(notedFor)) {
            notes.get(notedFor).clear();
        }
    }

    public void noteNumberForName(String notedFor, int noted) {
        notedNum.put(notedFor, noted);
    }
    public int getNotedNumberForName(String notedFor) {
        if (!notedNum.containsKey(notedFor)) {
            return 0;
        }
        return notedNum.get(notedFor);
    }

    public final CardCollectionView mill(int n, final ZoneType destination,
            final boolean bottom, SpellAbility sa, CardZoneTable table, Map<AbilityKey, Object> params) {
        final CardCollectionView lib = getCardsIn(ZoneType.Library);
        final CardCollection milled = new CardCollection();

        // Replacement effects
        final Map<AbilityKey, Object> repRunParams = AbilityKey.mapFromAffected(this);
        repRunParams.put(AbilityKey.Number, n);
        if (params != null) {
            repRunParams.putAll(params);
        }

        if (destination == ZoneType.Graveyard && !bottom) {
            switch (getGame().getReplacementHandler().run(ReplacementType.Mill, repRunParams)) {
                case NotReplaced:
                    break;
                case Updated:
                    // check if this is still the affected player
                    if (this.equals(repRunParams.get(AbilityKey.Affected))) {
                        n = (int) repRunParams.get(AbilityKey.Number);
                    } else {
                        return milled;
                    }
                    break;
                default:
                    return milled;
            }
        }

        final int max = Math.min(n, lib.size());

        for (int i = 0; i < max; i++) {
            if (bottom) {
                milled.add(lib.get(lib.size() - i - 1));
            } else {
                milled.add(lib.get(i));
            }
        }

        CardCollectionView milledView = milled;

        if (destination == ZoneType.Graveyard && milled.size() > 1) {
            milledView = GameActionUtil.orderCardsByTheirOwners(game, milled, ZoneType.Graveyard, sa);
        }

        for (Card m : milledView) {
            final ZoneType origin = m.getZone().getZoneType();
            final Card d = game.getAction().moveTo(destination, m, sa, params);
            if (d.getZone().is(destination)) {
                table.put(origin, d.getZone().getZoneType(), d);
            }
        }

        // MilledAll trigger
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.Cards, milled);
        game.getTriggerHandler().runTrigger(TriggerType.MilledAll, runParams, false);

        return milled;
    }

    public final CardCollection getTopXCardsFromLibrary(int amount) {
        final CardCollection topCards = new CardCollection();
        final PlayerZone lib = this.getZone(ZoneType.Library);
        int maxCards = lib.size();
        // If library is smaller than N, only get that many cards
        maxCards = Math.min(maxCards, amount);

        // show top n cards:
        for (int j = 0; j < maxCards; j++) {
            topCards.add(lib.get(j));
        }

        return topCards;
    }

    public final void shuffle(final SpellAbility sa) {
        final CardCollection list = new CardCollection(getCardsIn(ZoneType.Library));

        // Note: Shuffling once is sufficient.
        Collections.shuffle(list, MyRandom.getRandom());

        getZone(ZoneType.Library).setCards(getController().cheatShuffle(list));

        // Always Run triggers (701.20e)
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.Source, sa);
        game.getTriggerHandler().runTrigger(TriggerType.Shuffled, runParams, false);

        // Play the shuffle sound
        game.fireEvent(new GameEventShuffle(this));
    }

    public final boolean playLand(final Card land, final boolean ignoreZoneAndTiming) {
        // Dakkon Blackblade Avatar will use a similar effect
        if (canPlayLand(land, ignoreZoneAndTiming)) {
            playLandNoCheck(land, null);
            return true;
        }

        game.getStack().unfreezeStack();
        return false;
    }

    public final Card playLandNoCheck(final Card land, SpellAbility cause) {
        land.setController(this, 0);
        if (land.isFaceDown()) {
            land.turnFaceUp(null);
        }

        Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(land);
        runParams.put(AbilityKey.Origin, land.getZone().getZoneType().name());

        game.copyLastState();
        final Card c = game.getAction().moveTo(getZone(ZoneType.Battlefield), land, cause);
        game.updateLastStateForCard(c);

        // play a sound
        game.fireEvent(new GameEventLandPlayed(this, land));

        // Run triggers
        runParams.put(AbilityKey.SpellAbility, cause);
        game.getTriggerHandler().runTrigger(TriggerType.LandPlayed, runParams, false);
        game.getStack().unfreezeStack();
        addLandPlayedThisTurn();

        return c;
    }

    public final boolean canPlayLand(final Card land) {
        return canPlayLand(land, false);
    }
    public final boolean canPlayLand(final Card land, final boolean ignoreZoneAndTiming) {
        return canPlayLand(land, ignoreZoneAndTiming, null);
    }
    public final boolean canPlayLand(final Card land, final boolean ignoreZoneAndTiming, SpellAbility landSa) {
        if (!ignoreZoneAndTiming && !canCastSorcery()) {
            return false;
        }

        // CantBeCast static abilities
        if (StaticAbilityCantBeCast.cantPlayLandAbility(landSa, land, this)) {
            return false;
        }

        if (land != null && !ignoreZoneAndTiming) {
            final boolean mayPlay = landSa == null ? !land.mayPlay(this).isEmpty() : landSa.getMayPlay() != null;
            if (land.getOwner() != this && !mayPlay) {
                return false;
            }

            final Zone zone = game.getZoneOf(land);
            if (zone != null && (zone.is(ZoneType.Battlefield) || (!zone.is(ZoneType.Hand) && !mayPlay))) {
                return false;
            }
        }

        // **** Check for land play limit per turn ****
        // Dev Mode
        if (getMaxLandPlaysInfinite()) {
            return true;
        }

        // check for adjusted max lands play per turn
        return getLandsPlayedThisTurn() < getMaxLandPlays();
    }

    public final int getMaxLandPlays() {
        int adjMax = 1;
        for (Integer i : adjustLandPlays.values()) {
            adjMax += i;
        }
        return adjMax;
    }

    public final void addMaxLandPlays(long timestamp, int value) {
        adjustLandPlays.put(timestamp, value);
        getView().updateMaxLandPlay(this);
        getGame().fireEvent(new GameEventPlayerStatsChanged(this, false));
    }
    public final boolean removeMaxLandPlays(long timestamp) {
        boolean changed = adjustLandPlays.remove(timestamp) != null;
        if (changed) {
            getView().updateMaxLandPlay(this);
            getGame().fireEvent(new GameEventPlayerStatsChanged(this, false));
        }
        return changed;
    }

    public final void addMaxLandPlaysInfinite(long timestamp) {
        adjustLandPlaysInfinite.add(timestamp);
        getView().updateUnlimitedLandPlay(this);
        getGame().fireEvent(new GameEventPlayerStatsChanged(this, false));
    }
    public final boolean removeMaxLandPlaysInfinite(long timestamp) {
        boolean changed = adjustLandPlaysInfinite.remove(timestamp);
        if (changed) {
            getView().updateUnlimitedLandPlay(this);
            getGame().fireEvent(new GameEventPlayerStatsChanged(this, false));
        }
        return changed;
    }

    public final boolean getMaxLandPlaysInfinite() {
        if (getController().canPlayUnlimitedLands()) {
            return true;
        }
        return !adjustLandPlaysInfinite.isEmpty();
    }

    public final void addMaingameCardMapping(Card subgameCard, Card maingameCard) {
        maingameCardsMap.put(subgameCard, maingameCard);
    }

    public final Card getMappingMaingameCard(Card subgameCard) {
        return maingameCardsMap.get(subgameCard);
    }

    public final ManaPool getManaPool() {
        return manaPool;
    }
    public void updateManaForView() {
        view.updateMana(this);
    }

    public final int getNumPowerSurgeLands() {
        return numPowerSurgeLands;
    }
    public final int setNumPowerSurgeLands(final int n) {
        numPowerSurgeLands = n;
        return numPowerSurgeLands;
    }

    public final Card getLastDrawnCard() {
        return lastDrawnCard;
    }
    private final Card setLastDrawnCard(final Card c) {
        lastDrawnCard = c;
        return lastDrawnCard;
    }

    public final String getNamedCard() {
        return namedCard;
    }
    public final void setNamedCard(final String s) {
        namedCard = s;
    }
    public final String getNamedCard2() { return namedCard2; }
    public final void setNamedCard2(final String s) {
        namedCard2 = s;
    }

    public final int getTurn() {
        return stats.getTurnsPlayed();
    }

    public final void incrementTurn() {
        stats.nextTurn();
    }

    public boolean hasTappedLandForManaThisTurn() {
        return tappedLandForManaThisTurn;
    }
    public void setTappedLandForManaThisTurn(boolean tappedLandForManaThisTurn) {
        this.tappedLandForManaThisTurn = tappedLandForManaThisTurn;
    }

    public final boolean getActivateLoyaltyAbilityThisTurn() {
        return activateLoyaltyAbilityThisTurn;
    }
    public final void setActivateLoyaltyAbilityThisTurn(final boolean b) {
        activateLoyaltyAbilityThisTurn = b;
    }

    public final List<Card> getCreaturesAttackedThisTurn() {
        List<Card> result = Lists.newArrayList(Iterables.concat(attackedThisTurn.values()));
        return result;
    }
    public final List<Card> getCreaturesAttackedThisTurn(final GameEntity e) {
        return attackedThisTurn.getOrDefault(e, Lists.newArrayList());
    }
    public final void addCreaturesAttackedThisTurn(final Card c, final GameEntity e) {
        final List<Card> creatures = attackedThisTurn.getOrDefault(e, Lists.newArrayList());
        creatures.add(c);
        attackedThisTurn.putIfAbsent(e, creatures);
        if (e instanceof Player && !attackedPlayersThisCombat.contains(e)) {
            attackedPlayersThisCombat.add((Player) e);
        }
    }

    public final Iterable<Player> getAttackedPlayersMyTurn() {
        return Iterables.filter(attackedThisTurn.keySet(), Player.class);
    }
    public final List<Player> getAttackedPlayersMyLastTurn() {
        return attackedPlayersLastTurn;
    }
    public final void clearAttackedMyTurn() {
        attackedThisTurn.clear();
    }
    public final void setAttackedPlayersMyLastTurn(Iterable<Player> players) {
        attackedPlayersLastTurn.clear();
        Iterables.addAll(attackedPlayersLastTurn, players);
    }

    public final List<Player> getAttackedPlayersMyCombat() {
        return attackedPlayersThisCombat;
    }
    public final void clearAttackedPlayersMyCombat() {
        attackedPlayersThisCombat.clear();
    }

    public final int getVenturedThisTurn() {
        return venturedThisTurn;
    }
    public final void incrementVenturedThisTurn() {
        venturedThisTurn++;
    }
    public final void resetVenturedThisTurn() {
        venturedThisTurn = 0;
    }

    public final List<Card> getCompletedDungeons() {
        return completedDungeons;
    }
    public void addCompletedDungeon(Card dungeon) {
        completedDungeons.add(dungeon);
    }
    public void resetCompletedDungeons() {
        completedDungeons.clear();
    }

    public final void altWinBySpellEffect(final String sourceName) {
        if (cantWin()) {
            System.out.println("Tried to win, but currently can't.");
            return;
        }
        setOutcome(PlayerOutcome.altWin(sourceName));
    }

    public final boolean loseConditionMet(final GameLossReason state, final String spellName) {
        if (state != GameLossReason.OpponentWon) {
            if (cantLose()) {
                System.out.println("Tried to lose, but currently can't.");
                return false;
            }

            // Replacement effects
            if (game.getReplacementHandler().run(ReplacementType.GameLoss, AbilityKey.mapFromAffected(this)) != ReplacementResult.NotReplaced) {
                return false;
            }
        }
        setOutcome(PlayerOutcome.loss(state, spellName));
        return true;
    }

    public final void concede() { // No cantLose checks - just lose
        setOutcome(PlayerOutcome.concede());
    }

    public final void intentionalDraw() {
        setOutcome(PlayerOutcome.draw());
    }

    public final boolean cantLose() {
        if (getOutcome() != null && getOutcome().lossState == GameLossReason.Conceded) {
            return false;
        }
        return hasKeyword("You can't lose the game.");
    }

    public final boolean cantLoseForZeroOrLessLife() {
        return cantLose() || hasKeyword("You don't lose the game for having 0 or less life.");
    }

    public final boolean cantWin() {
        boolean isAnyOppLoseProof = false;
        for (Player p : game.getPlayers()) {
            if (p == this || p.getOutcome() != null) {
                continue; // except self and already dead
            }
            isAnyOppLoseProof |= p.hasKeyword("You can't lose the game.");
        }
        return hasKeyword("You can't win the game.") || isAnyOppLoseProof;
    }

    public final boolean checkLoseCondition() {
        // Just in case player already lost
        if (getOutcome() != null) {
            return getOutcome().lossState != null;
        }

        // check this first because of Lich's Mirror (704.7)
        // Rule 704.5b - If a player attempted to draw a card from a library with no cards in it
        //               since the last time state-based actions were checked, he or she loses the game.
        if (triedToDrawFromEmptyLibrary) {
            triedToDrawFromEmptyLibrary = false; // one-shot check
            // Mine, Mine, Mine! prevents decking
            if (!hasKeyword("You don't lose the game for drawing from an empty library.")) {
                return loseConditionMet(GameLossReason.Milled, null);
            }
        }

        // Rule 704.5a -  If a player has 0 or less life, he or she loses the game.
        final boolean hasNoLife = getLife() <= 0;
        if (hasNoLife && !cantLoseForZeroOrLessLife()) {
            return loseConditionMet(GameLossReason.LifeReachedZero, null);
        }

        // Rule 704.5c - If a player has ten or more poison counters, he or she loses the game.
        if (getCounters(CounterEnumType.POISON) >= 10) {
            return loseConditionMet(GameLossReason.Poisoned, null);
        }

        if (game.getRules().hasAppliedVariant(GameType.Commander)) {
            for (Entry<Card, Integer> entry : getCommanderDamage()) {
                if (entry.getValue() >= 21) {
                    return loseConditionMet(GameLossReason.CommanderDamage, null);
                }
            }
        }
        return false;
    }

    public final boolean hasLost() {
        return getOutcome() != null && getOutcome().lossState != null;
    }

    public final boolean hasWon() {
        if (cantWin()) {
            return false;
        }
        // in multiplayer game one player's win is replaced by all other's lose (rule 103.4h)
        // so if someone cannot lose, the game appears to continue
        return getOutcome() != null && getOutcome().lossState == null;
    }

    public final boolean isInGame() {
        return getOutcome() == null;
    }

    public final boolean hasMetalcraft() {
        return CardLists.count(getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.ARTIFACTS) >= 3;
    }

    public final boolean hasDesert() {
        return Iterables.any(
                getCardsIn(Arrays.asList(ZoneType.Battlefield, ZoneType.Graveyard)),
                CardPredicates.isType("Desert"));
    }

    public final boolean hasThreshold() {
        return getZone(ZoneType.Graveyard).size() >= 7;
    }

    public final boolean hasHellbent() {
        return getZone(ZoneType.Hand).isEmpty();
    }

    public final boolean hasRevolt() {
        return revolt;
    }
    public final void setRevolt(final boolean val) {
        revolt = val;
    }

    public final boolean hasDelirium() {
        return CardFactoryUtil.getCardTypesFromList(getCardsIn(ZoneType.Graveyard)) >= 4;
    }

    public final boolean hasLandfall() {
        return Iterables.any(getZone(ZoneType.Battlefield).getCardsAddedThisTurn(null), CardPredicates.Presets.LANDS);
    }

    public boolean hasFerocious() {
        return !CardLists.filterPower(getCreaturesInPlay(), 4).isEmpty();
    }

    public final boolean hasSurge() {
        return !CardLists.filterControlledBy(game.getStack().getSpellsCastThisTurn(), getYourTeam()).isEmpty();
    }

    public final boolean hasBloodthirst() {
        for (Player p : getRegisteredOpponents()) {
            if (p.getAssignedDamage() > 0) {
                return true;
            }
        }
        return false;
    }

    public final int getBloodthirstAmount() {
        return Aggregates.sum(getRegisteredOpponents(), Accessors.FN_GET_ASSIGNED_DAMAGE);
    }

    public final int getOpponentLostLifeThisTurn() {
        int lost = 0;
        for (Player opp : getRegisteredOpponents()) {
            lost += opp.getLifeLostThisTurn();
        }
        return lost;
    }

    public final boolean hasProwl(final Set<String> types) {
        StringBuilder sb = new StringBuilder();
        for (String type : types) {
            sb.append("Card.YouCtrl+").append(type).append(",");
        }
        return !game.getDamageDoneThisTurn(true, true, sb.toString(), "Player", null, this, null).isEmpty();
    }

    public final void setLibrarySearched(final int l) {
        numLibrarySearchedOwn = l;
    }
    public final int getLibrarySearched() {
        return numLibrarySearchedOwn;
    }
    public final void incLibrarySearched() {
        numLibrarySearchedOwn++;
    }

    public final void setNumManaConversion(final int l) {
        numManaConversion = l;
    }
    public final boolean hasManaConversion() {
        return numManaConversion < keywords.getAmount("You may spend mana as though"
                + " it were mana of any color to cast a spell this turn.");
    }
    public final void incNumManaConversion() {
        numManaConversion++;
    }
    public final void decNumManaConversion() {
        numManaConversion--;
    }

    @Override
    public final boolean isValid(final String restriction, final Player sourceController, final Card source, CardTraitBase spellAbility) {
        final String[] incR = restriction.split("\\.", 2);

        if (incR[0].equals("Opponent")) {
            if (equals(sourceController) || !isOpponentOf(sourceController)) {
                return false;
            }
        } else if (incR[0].equals("You")) {
            if (!equals(sourceController)) {
                return false;
            }
        } else {
            if (!incR[0].equals("Player")) {
                return false;
            }
        }

        if (incR.length > 1) {
            final String excR = incR[1];
            final String[] exR = excR.split("\\+"); // Exclusive Restrictions are ...
            for (int j = 0; j < exR.length; j++) {
                if (!hasProperty(exR[j], sourceController, source, spellAbility)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public final boolean hasProperty(final String property, final Player sourceController, final Card source, CardTraitBase spellAbility) {
        if (property.startsWith("!")) {
            return !PlayerProperty.playerHasProperty(this, property.substring(1), sourceController, source, spellAbility);
        }
        return PlayerProperty.playerHasProperty(this, property, sourceController, source, spellAbility);
    }

    public final int getMaxHandSize() {
        return maxHandSize;
    }
    public final void setMaxHandSize(int size) {
        if (maxHandSize == size) { return; }
        maxHandSize = size;
        view.updateMaxHandSize(this);
    }

    public boolean isUnlimitedHandSize() {
        return unlimitedHandSize;
    }
    public void setUnlimitedHandSize(boolean unlimited) {
        if (unlimitedHandSize == unlimited) { return; }
        unlimitedHandSize = unlimited;
        view.updateUnlimitedHandSize(this);
    }

    public final int getLandsPlayedThisTurn() {
        return landsPlayedThisTurn;
    }
    public final int getLandsPlayedLastTurn() {
        return landsPlayedLastTurn;
    }
    public final void addLandPlayedThisTurn() {
        landsPlayedThisTurn++;
        achievementTracker.landsPlayed++;
        view.updateNumLandThisTurn(this);
    }
    public final void resetLandsPlayedThisTurn() {
        landsPlayedThisTurn = 0;
        view.updateNumLandThisTurn(this);
    }
    public final void setLandsPlayedThisTurn(int num) {
        // This method should only be used directly when setting up the game state.
        landsPlayedThisTurn = num;

        view.updateNumLandThisTurn(this);
    }
    public final void setLandsPlayedLastTurn(int num) {
        landsPlayedLastTurn = num;
    }

    public final int getInvestigateNumThisTurn() {
        return investigatedThisTurn;
    }
    public final void addInvestigatedThisTurn() {
        investigatedThisTurn++;
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        runParams.put(AbilityKey.Num, investigatedThisTurn);
        game.getTriggerHandler().runTrigger(TriggerType.Investigated, runParams, false);
    }
    public final void resetInvestigatedThisTurn() {
        investigatedThisTurn = 0;
    }

    public final void addSacrificedThisTurn(final Card c, final SpellAbility source) {
        // Play the Sacrifice sound
        game.fireEvent(new GameEventCardSacrificed());

        final Card cpy = CardUtil.getLKICopy(c);
        sacrificedThisTurn.add(cpy);

        // Run triggers
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
        // use a copy that preserves last known information about the card (e.g. for Savra, Queen of the Golgari + Painter's Servant)
        runParams.put(AbilityKey.Card, cpy);
        runParams.put(AbilityKey.Cause, source);
        runParams.put(AbilityKey.CostStack, game.costPaymentStack);
        runParams.put(AbilityKey.IndividualCostPaymentInstance, game.costPaymentStack.peek());
        game.getTriggerHandler().runTrigger(TriggerType.Sacrificed, runParams, false);
    }

    public final List<Card> getSacrificedThisTurn() {
        return sacrificedThisTurn;
    }
    public final void resetSacrificedThisTurn() {
        sacrificedThisTurn.clear();
    }

    public final int getSpellsCastThisTurn() {
        return spellsCastThisTurn;
    }
    public final int getSpellsCastLastTurn() {
        return spellsCastLastTurn;
    }
    public final void addSpellCastThisTurn() {
        spellsCastThisTurn++;
        spellsCastThisGame++;
        achievementTracker.spellsCast++;
        if (spellsCastThisTurn > achievementTracker.maxStormCount) {
            achievementTracker.maxStormCount = spellsCastThisTurn;
        }
    }
    public final void resetSpellsCastThisTurn() {
        spellsCastThisTurn = 0;
    }
    public final void setSpellsCastLastTurn(int num) {
        spellsCastLastTurn = num;
    }
    public final int getSpellsCastThisGame() {
        return spellsCastThisGame;
    }
    public final void resetSpellCastThisGame() {
        spellsCastThisGame = 0;
    }

    public final int getLifeGainedByTeamThisTurn() {
        return lifeGainedByTeamThisTurn;
    }
    public final void addLifeGainedByTeamThisTurn(int val) {
        lifeGainedByTeamThisTurn += val;
    }

    public final int getLifeGainedThisTurn() {
        return lifeGainedThisTurn;
    }
    public final void setLifeGainedThisTurn(final int n) {
        lifeGainedThisTurn = n;
    }

    public final int getLifeGainedTimesThisTurn() {
        return lifeGainedTimesThisTurn;
    }

    public final int getLifeLostThisTurn() {
        return lifeLostThisTurn;
    }
    public final void setLifeLostThisTurn(final int n) {
        lifeLostThisTurn = n;
    }

    public final int getLifeLostLastTurn() {
        return lifeLostLastTurn;
    }
    public final void setLifeLostLastTurn(final int n) {
        lifeLostLastTurn = n;
    }

    @Override
    public int compareTo(Player o) {
        if (o == null) {
            return 1;
        }
        return getName().compareTo(o.getName());
    }

    public static class Accessors {
        public static Function<Player, String> FN_GET_NAME = new Function<Player, String>() {
            @Override
            public String apply(Player input) {
                return input.getName();
            }
        };
        public static Function<Player, Integer> FN_GET_LIFE_TOTAL = new Function<Player, Integer>() {
            @Override
            public Integer apply(Player input) {
                return input.getLife();
            }
        };
        public static Function<Player, Integer> FN_GET_POISON_COUNTERS = new Function<Player, Integer>() {
            @Override
            public Integer apply(Player input) {
                return input.getPoisonCounters();
            }
        };
        public static final Function<Player, Integer> FN_GET_ASSIGNED_DAMAGE = new Function<Player, Integer>() {
            @Override
            public Integer apply(Player input) {
                return input.getAssignedDamage();
            }
        };
    }

    public final LobbyPlayer getLobbyPlayer() {
        return getController().getLobbyPlayer();
    }

    public final LobbyPlayer getOriginalLobbyPlayer() {
        return controller.getLobbyPlayer();
    }

    public final RegisteredPlayer getRegisteredPlayer() {
        return game.getMatch().getPlayers().get(game.getRegisteredPlayers().indexOf(this));
    }

    private void setOutcome(PlayerOutcome outcome) {
        stats.setOutcome(outcome);
    }

    public void onGameOver() {
        if (null == stats.getOutcome()) {
            setOutcome(PlayerOutcome.win());
        }
    }

    /**
     * use to get a list of creatures in play for a given player.
     */
    public CardCollection getCreaturesInPlay() {
        return CardLists.filter(getCardsIn(ZoneType.Battlefield), Presets.CREATURES);
    }

    public CardCollection getPlaneswalkersInPlay() {
        return CardLists.filter(getCardsIn(ZoneType.Battlefield), Presets.PLANESWALKERS);
    }

    /**
     * use to get a list of tokens in play for a given player.
     */
    public CardCollection getTokensInPlay() {
        return CardLists.filter(getCardsIn(ZoneType.Battlefield), Presets.TOKEN);
    }

    /**
     * use to get a list of all lands a given player has on the battlefield.
     */
    public CardCollection getLandsInPlay() {
        return CardLists.filter(getCardsIn(ZoneType.Battlefield), Presets.LANDS);
    }

    public boolean isCardInPlay(final String cardName) {
        return getZone(ZoneType.Battlefield).contains(CardPredicates.nameEquals(cardName));
    }

    public boolean isCardInCommand(final String cardName) {
        return getZone(ZoneType.Command).contains(CardPredicates.nameEquals(cardName));
    }

    public CardCollectionView getColoredCardsInPlay(final String color) {
        return getColoredCardsInPlay(MagicColor.fromName(color));
    }
    public CardCollectionView getColoredCardsInPlay(final byte color) {
        return CardLists.getColor(getCardsIn(ZoneType.Battlefield), color);
    }

    public final int getAmountOfKeyword(final String k) {
        return keywords.getAmount(k);
    }

    public final int getLastTurnNr() {
        return this.lastTurnNr;
    }

    public void onCleanupPhase() {
        for (Card c : getCardsIn(ZoneType.Hand)) {
            c.setDrawnThisTurn(false);
        }
        for (final PlayerZone pz : zones.values()) {
            pz.resetCardsAddedThisTurn();
        }
        resetNumDrawnThisTurn();
        resetNumRollsThisTurn();
        resetNumDiscardedThisTurn();
        resetNumForetoldThisTurn();
        resetNumTokenCreatedThisTurn();
        setNumCardsInHandStartedThisTurnWith(getCardsIn(ZoneType.Hand).size());
        setActivateLoyaltyAbilityThisTurn(false);
        setTappedLandForManaThisTurn(false);
        setLandsPlayedLastTurn(getLandsPlayedThisTurn());
        resetLandsPlayedThisTurn();
        resetInvestigatedThisTurn();
        resetSurveilThisTurn();
        resetCycledThisTurn();
        resetEquippedThisTurn();
        resetSacrificedThisTurn();
        resetVenturedThisTurn();
        setRevolt(false);
        setSpellsCastLastTurn(getSpellsCastThisTurn());
        resetSpellsCastThisTurn();
        setLifeLostLastTurn(getLifeLostThisTurn());
        setLifeLostThisTurn(0);
        setLifeGainedThisTurn(0);
        lifeGainedTimesThisTurn = 0;
        lifeGainedByTeamThisTurn = 0;
        setLifeStartedThisTurnWith(getLife());
        setLibrarySearched(0);
        setNumManaConversion(0);

        damageReceivedThisTurn.clear();

        // set last turn nr
        if (game.getPhaseHandler().isPlayerTurn(this)) {
            setAttackedPlayersMyLastTurn(getAttackedPlayersMyTurn());
            clearAttackedMyTurn();
            this.lastTurnNr = game.getPhaseHandler().getTurn();
        }
    }

    public boolean canCastSorcery() {
        PhaseHandler now = game.getPhaseHandler();
        return now.isPlayerTurn(this) && now.getPhase().isMain() && game.getStack().isEmpty();
    }

    //NOTE: for conditions the stack must only have the sa being checked
    public boolean couldCastSorcery(final SpellAbility sa) {
        final Card source = sa.getRootAbility().getHostCard();

        for (final Card card : game.getCardsIn(ZoneType.Stack)) {
            if (!card.equals(source)) {
                return false;
            }
        }

        PhaseHandler now = game.getPhaseHandler();
        return now.isPlayerTurn(this) && now.getPhase().isMain();
    }

    public final PlayerController getController() {
        if (!controlledBy.isEmpty()) {
            return controlledBy.lastEntry().getValue().getValue();
        }
        return controller;
    }

    public final Player getControllingPlayer() {
        if (!controlledBy.isEmpty()) {
            return controlledBy.lastEntry().getValue().getKey();
        }
        return null;
    }

    public final boolean isControlled() {
        Player ctrlPlayer = this.getControllingPlayer();
        return ctrlPlayer != null && ctrlPlayer != this;
    }

    public void addController(long timestamp, Player pl) {
        final IGameEntitiesFactory master = (IGameEntitiesFactory)pl.getLobbyPlayer();
        addController(timestamp, pl, master.createMindSlaveController(pl, this), true);
    }

    public void addController(long timestamp, Player pl, PlayerController pc, boolean event) {
        final LobbyPlayer oldLobbyPlayer = getLobbyPlayer();
        final PlayerController oldController = getController();

        controlledBy.put(timestamp, Pair.of(pl, pc));
        getView().updateMindSlaveMaster(this);

        if (event) {
            game.fireEvent(new GameEventPlayerControl(this, oldLobbyPlayer, oldController, getLobbyPlayer(), getController()));
        }
    }

    public void removeController(long timestamp) {
        removeController(timestamp, true);
    }
    public void removeController(long timestamp, boolean event) {
        final LobbyPlayer oldLobbyPlayer = getLobbyPlayer();
        final PlayerController oldController = getController();

        controlledBy.remove(timestamp);
        getView().updateMindSlaveMaster(this);

        if (event) {
            game.fireEvent(new GameEventPlayerControl(this, oldLobbyPlayer, oldController, getLobbyPlayer(), getController()));
        }
    }

    public void clearController() {
        controlledBy.clear();
        game.fireEvent(new GameEventPlayerControl(this, null, null, getLobbyPlayer(), getController()));
    }

    public Map.Entry<Long, Player> getControlledWhileSearching() {
        if (controlledWhileSearching.isEmpty()) {
            return null;
        }
        return controlledWhileSearching.lastEntry();
    }

    public void addControlledWhileSearching(long timestamp, Player pl) {
        controlledWhileSearching.put(timestamp, pl);
    }

    public void removeControlledWhileSearching(long timestamp) {
        controlledWhileSearching.remove(timestamp);
    }

    public final void setFirstController(PlayerController ctrlr) {
        if (controller != null) {
            throw new IllegalStateException("Controller creator already assigned");
        }
        controller = ctrlr;
        updateAvatar();
        updateSleeve();
        view.updateIsAI(this);
        view.updateLobbyPlayerName(this);
    }

    public void updateAvatar() {
        view.updateAvatarIndex(this);
        view.updateAvatarCardImageKey(this);
        view.setAvatarLifeDifference(0);
        view.setHasLost(false);
    }

    public void updateSleeve() {
        view.updateSleeveIndex(this);
    }

    /**
     * Run a procedure using a different controller
     */
    public void runWithController(Runnable proc, PlayerController tempController) {
        long ts = game.getNextTimestamp();
        addController(ts, this, tempController, false);
        try {
            proc.run();
        } finally {
            removeController(ts, false);
        }
    }

    public boolean isSkippingCombat() {
        return !isInGame();
    }

    public int getStartingHandSize() {
        return startingHandSize;
    }
    public void setStartingHandSize(int shs) {
        startingHandSize = shs;
    }

    /**
     * Takes the top plane of the planar deck and put it face up in the command zone.
     * Then runs triggers.
     */
    public void planeswalk(SpellAbility sa) {
        planeswalkTo(sa, new CardCollection(getZone(ZoneType.PlanarDeck).get(0)));
    }

    /**
     * Puts the planes in the argument and puts them face up in the command zone.
     * Then runs triggers.
     */
    public void planeswalkTo(SpellAbility sa, final CardCollectionView destinations) {
        System.out.println(getName() + ": planeswalk to " + destinations.toString());
        currentPlanes.addAll(destinations);
        game.getView().updatePlanarPlayer(getView());

        Map<AbilityKey, Object> moveParams = AbilityKey.newMap();
        moveParams.put(AbilityKey.LastStateBattlefield, sa.getLastStateBattlefield());
        moveParams.put(AbilityKey.LastStateGraveyard, sa.getLastStateGraveyard());

        for (Card c : currentPlanes) {
            game.getAction().moveTo(ZoneType.Command, c, sa, moveParams);
            //getZone(ZoneType.PlanarDeck).remove(c);
            //getZone(ZoneType.Command).add(c);
        }

        game.setActivePlanes(currentPlanes);
        //Run PlaneswalkedTo triggers here.
        final Map<AbilityKey, Object> runParams = AbilityKey.newMap();
        runParams.put(AbilityKey.Cards, currentPlanes);
        game.getTriggerHandler().runTrigger(TriggerType.PlaneswalkedTo, runParams, false);
        view.updateCurrentPlaneName(currentPlanes.toString().replaceAll(" \\(.*","").replace("[",""));
    }

    /**
     * Puts my currently active planes, if any, at the bottom of my planar deck.
     */
    public void leaveCurrentPlane() {
        final Map<AbilityKey, Object> runParams = AbilityKey.newMap();
        runParams.put(AbilityKey.Cards, new CardCollection(currentPlanes));
        game.getTriggerHandler().runTrigger(TriggerType.PlaneswalkedFrom, runParams, false);

        for (final Card plane : currentPlanes) {
            //game.getZoneOf(plane).remove(plane);
            plane.clearControllers();
            //getZone(ZoneType.PlanarDeck).add(plane);
            game.getAction().moveTo(ZoneType.PlanarDeck, plane,-1, null);
        }
        currentPlanes.clear();
    }

    /**
     * Sets up the first plane of a round.
     */
    public void initPlane() {
        Card firstPlane = null;
        view.updateCurrentPlaneName("");
        game.getView().updatePlanarPlayer(getView());

        while (true) {
            firstPlane = getZone(ZoneType.PlanarDeck).get(0);
            getZone(ZoneType.PlanarDeck).remove(firstPlane);
            if (firstPlane.getType().isPhenomenon()) {
                getZone(ZoneType.PlanarDeck).add(firstPlane);
            }
            else {
                currentPlanes.add(firstPlane);
                getZone(ZoneType.Command).add(firstPlane);
                break;
            }
        }
        game.setActivePlanes(currentPlanes);
        view.updateCurrentPlaneName(currentPlanes.toString().replaceAll(" \\(.*","").replace("[",""));
    }

    public final void resetCombatantsThisCombat() {
        // resets the status of attacked/blocked this phase
        CardCollectionView list = getCardsIn(ZoneType.Battlefield, false);

        for (Card c : list) {
            if (c.getDamageHistory().getCreatureAttackedThisCombat() > 0) {
                c.getDamageHistory().setCreatureAttackedThisCombat(null, -1);
            }
            if (c.getDamageHistory().getCreatureBlockedThisCombat()) {
                c.getDamageHistory().setCreatureBlockedThisCombat(false);
            }
            if (c.getDamageHistory().getCreatureGotBlockedThisCombat()) {
                c.getDamageHistory().setCreatureGotBlockedThisCombat(false);
            }
        }
    }

    public CardCollectionView getInboundTokens() {
        return inboundTokens;
    }
    public void addInboundToken(Card c) {
        inboundTokens.add(c);
    }
    public void removeInboundToken(Card c) {
        inboundTokens.remove(c);
    }

    public void onMulliganned() {
        game.fireEvent(new GameEventMulligan(this)); // quest listener may interfere here
        final int newHand = getCardsIn(ZoneType.Hand).size();
        stats.notifyHasMulliganed();
        stats.notifyOpeningHandSize(newHand);
        achievementTracker.mulliganTo = newHand;
    }

    public List<Card> getCommanders() {
        return commanders;
    }
    public void setCommanders(List<Card> commanders0) {
        if (commanders0 == commanders) { return; }
        commanders = commanders0;
        view.updateCommander(this);
    }

    public Iterable<Entry<Card, Integer>> getCommanderDamage() {
        return commanderDamage.entrySet();
    }
    public int getCommanderDamage(Card commander) {
        Integer damage = commanderDamage.get(commander);
        return damage == null ? 0 : damage.intValue();
    }
    public void addCommanderDamage(Card commander, int damage) {
        commanderDamage.merge(commander, damage, Integer::sum);
    }

    public ColorSet getCommanderColorID() {
        if (commanders.isEmpty()) {
            return null;
        }
        byte ci = 0;
        for (final Card c : commanders) {
            ci |= c.getRules().getColorIdentity().getColor();
        }
        ColorSet identity = ColorSet.fromMask(ci);
        return identity;
    }
    public ColorSet getNotCommanderColorID() {
        if (commanders.isEmpty()) {
            return null;
        }
        ColorSet identity = getCommanderColorID();
        return identity.inverse();
    }

    public int getCommanderCast(Card commander) {
        Integer cast = commanderCast.get(commander);
        return cast == null ? 0 : cast.intValue();
    }
    public void incCommanderCast(Card commander) {
        commanderCast.put(commander, getCommanderCast(commander) + 1);
        getView().updateCommanderCast(this, commander);
        getGame().fireEvent(new GameEventPlayerStatsChanged(this, false));
    }

    public void resetCommanderStats() {
        commanderCast.clear();
        commanderDamage.clear();
    }

    public void updateMergedCommanderInfo(Card target, Card commander) {
        getView().updateMergedCommanderCast(this, target, commander);
        getView().updateMergedCommanderDamage(target, commander);
    }

    public int getTotalCommanderCast() {
        int result = 0;
        for (Integer i : commanderCast.values()) {
            result += i;
        }
        return result;
    }

    public boolean isExtraTurn() {
        return view.getIsExtraTurn();
    }
    public void setExtraTurn(boolean b) {
        view.setIsExtraTurn(b);
    }

    public void setHasLost(boolean b) {
        view.setHasLost(b);
    }

    public void setAvatarLifeDifference(int val) {
        view.setAvatarLifeDifference(val);
    }

    public int getExtraTurnCount() {
        return view.getExtraTurnCount();
    }
    public void setExtraTurnCount(final int val) {
        view.setExtraTurnCount(val);
    }

    public void setHasPriority(final boolean val) {
        view.setHasPriority(val);
    }

    public boolean isAI() {
        return view.isAI();
    }

    public void initVariantsZones(RegisteredPlayer registeredPlayer) {
        PlayerZone bf = getZone(ZoneType.Battlefield);
        Iterable<? extends IPaperCard> cards = registeredPlayer.getCardsOnBattlefield();
        if (cards != null) {
            for (final IPaperCard cp : cards) {
                Card c = Card.fromPaperCard(cp, this);
                bf.add(c);
                c.setSickness(true);
                c.setStartsGameInPlay(true);
            }
        }

        PlayerZone com = getZone(ZoneType.Command);

        // Vanguard
        if (registeredPlayer.getVanguardAvatars() != null) {
            for (PaperCard avatar:registeredPlayer.getVanguardAvatars()) {
                com.add(Card.fromPaperCard(avatar, this));
            }
        }

        // Schemes
        CardCollection sd = new CardCollection();
        for (IPaperCard cp : registeredPlayer.getSchemes()) {
            sd.add(Card.fromPaperCard(cp, this));
        }
        if (!sd.isEmpty()) {
            for (Card c : sd) {
                getZone(ZoneType.SchemeDeck).add(c);
            }
            getZone(ZoneType.SchemeDeck).shuffle();
        }

        // Planes
        CardCollection l = new CardCollection();
        for (IPaperCard cp : registeredPlayer.getPlanes()) {
            l.add(Card.fromPaperCard(cp, this));
        }
        if (!l.isEmpty()) {
            for (Card c : l) {
                getZone(ZoneType.PlanarDeck).add(c);
            }
            getZone(ZoneType.PlanarDeck).shuffle();
        }

        // Commander
        if (!registeredPlayer.getCommanders().isEmpty()) {
            List<Card> commanders = Lists.newArrayList();
            for (PaperCard pc : registeredPlayer.getCommanders()) {
                Card cmd = Card.fromPaperCard(pc, this);
                if (cmd.hasKeyword("If CARDNAME is your commander, choose a color before the game begins.")) {
                    Player p = cmd.getController();
                    List<String> colorChoices = new ArrayList<>(MagicColor.Constant.ONLY_COLORS);
                    String prompt = Localizer.getInstance().getMessage("lblChooseAColorFor", cmd.getName());
                    List<String> chosenColors;
                    SpellAbility cmdColorsa = new SpellAbility.EmptySa(ApiType.ChooseColor, cmd, p);
                    chosenColors = p.getController().chooseColors(prompt,cmdColorsa, 1, 1, colorChoices);
                    cmd.setChosenColors(chosenColors);
                    p.getGame().getAction().notifyOfValue(cmdColorsa, cmd, Localizer.getInstance().getMessage("lblPlayerPickedChosen", p.getName(), Lang.joinHomogenous(chosenColors)), p);
                }
                cmd.setCommander(true);
                com.add(cmd);
                commanders.add(cmd);
                com.add(createCommanderEffect(game, cmd));
            }
            this.setCommanders(commanders);
        }
        else if (registeredPlayer.getPlaneswalker() != null) { // Planeswalker
            Card cmd = Card.fromPaperCard(registeredPlayer.getPlaneswalker(), this);
            cmd.setCommander(true);
            com.add(cmd);
            setCommanders(Lists.newArrayList(cmd));
            com.add(createCommanderEffect(game, cmd));
        }

        // Conspiracies
        for (IPaperCard cp : registeredPlayer.getConspiracies()) {
            Card conspire = Card.fromPaperCard(cp, this);
            if (conspire.hasKeyword("Hidden agenda") || conspire.hasKeyword("Double agenda")) {
                if (!CardFactoryUtil.handleHiddenAgenda(this, conspire)) {
                    continue;
                }
            }
            com.add(conspire);
        }

        for (final Card c : getCardsIn(ZoneType.Library)) {
            for (KeywordInterface inst : c.getKeywords()) {
                String kw = inst.getOriginal();
                if (kw.startsWith("MayEffectFromOpeningDeck")) {
                    String[] split = kw.split(":");
                    final String effName = split[1];

                    final SpellAbility effect = AbilityFactory.getAbility(c.getSVar(effName), c);
                    effect.setActivatingPlayer(this);

                    getController().playSpellAbilityNoStack(effect, true);
                }
            }
        }
    }

    public boolean allCardsUniqueManaSymbols() {
        for (final Card c : getCardsIn(ZoneType.Library)) {
            Set<CardStateName> cardStateNames = c.isSplitCard() ?  EnumSet.of(CardStateName.LeftSplit, CardStateName.RightSplit) : EnumSet.of(CardStateName.Original);
        	Set<ManaCostShard> coloredManaSymbols = new HashSet<>();
        	Set<Integer> genericManaSymbols = new HashSet<>();

        	for (final CardStateName cardStateName : cardStateNames) {
        		final ManaCost manaCost = c.getState(cardStateName).getManaCost();
	        	for (final ManaCostShard manaSymbol : manaCost) {
	        		if (!coloredManaSymbols.add(manaSymbol)) {
	        			return false;
	        		}
	        	}
	        	int generic = manaCost.getGenericCost();
	        	if (generic > 0 || manaCost.getCMC() == 0) {
	        		if (!genericManaSymbols.add(Integer.valueOf(generic))) {
	        			return false;
	        		}
	        	}
        	}
        }
        return true;
    }

    public Card assignCompanion(Game game, PlayerController player) {
        List<Card> legalCompanions = Lists.newArrayList();

        boolean uniqueNames = true;
        Set<String> cardNames = new HashSet<>();
        Set<CardType.CoreType> cardTypes = EnumSet.allOf(CardType.CoreType.class);
        final CardCollection nonLandInDeck = CardLists.getNotType(getCardsIn(ZoneType.Library), "Land");
        for (final Card c : nonLandInDeck) {
            if (uniqueNames) {
                if (cardNames.contains(c.getName())) {
                    uniqueNames = false;
                } else {
                    cardNames.add(c.getName());
                }
            }

            cardTypes.retainAll((Collection<?>) c.getPaperCard().getRules().getType().getCoreTypes());
        }

        int deckSize = getCardsIn(ZoneType.Library).size();
        int minSize = game.getMatch().getRules().getGameType().getDeckFormat().getMainRange().getMinimum();

        game.getAction().checkStaticAbilities(false);

        for (final Card c : getCardsIn(ZoneType.Sideboard)) {
            for (KeywordInterface inst : c.getKeywords(Keyword.COMPANION)) {
                if (!(inst instanceof Companion)) {
                    continue;
                }

                Companion kwInstance = (Companion) inst;
                if (kwInstance.hasSpecialRestriction()) {
                    String specialRules = kwInstance.getSpecialRules();
                    if (specialRules.equals("UniqueNames")) {
                        if (uniqueNames) {
                            legalCompanions.add(c);
                        }
                    } else if (specialRules.equals("UniqueManaSymbols")) {
                    	if (this.allCardsUniqueManaSymbols()) {
                    		legalCompanions.add(c);
                    	}
                    } else if (specialRules.equals("DeckSizePlus20")) {
                        // +20 deck size to min deck size
                        if (deckSize >= minSize + 20) {
                            legalCompanions.add(c);
                        }
                    } else if (specialRules.equals("SharesCardType")) {
                        // Shares card type
                        if (!cardTypes.isEmpty()) {
                            legalCompanions.add(c);
                        }
                    }

                } else {
                    String restriction = kwInstance.getDeckRestriction();
                    if (deckMatchesDeckRestriction(c, restriction)) {
                        legalCompanions.add(c);
                    }
                }
            }
        }

        if (legalCompanions.isEmpty()) {
            return null;
        }

        CardCollectionView view = CardCollection.getView(legalCompanions);

        SpellAbility fakeSa = new SpellAbility.EmptySa(ApiType.CompanionChoose, legalCompanions.get(0), this);
        return player.chooseSingleEntityForEffect(view, fakeSa, Localizer.getInstance().getMessage("lblChooseACompanion"), true, null);
    }

    public boolean deckMatchesDeckRestriction(Card source, String restriction) {
        for (final Card c : getCardsIn(ZoneType.Library)) {
            if (!c.isValid(restriction.split(","), this, source, null)) {
                return false;
            }
        }
        return true;
     }

    public static DetachedCardEffect createCompanionEffect(Game game, Card companion) {
        final String name = Lang.getInstance().getPossesive(companion.getName()) + " Companion Effect";
        DetachedCardEffect eff = new DetachedCardEffect(companion, name);

        String addToHandAbility = "Mode$ Continuous | EffectZone$ Command | Affected$ Card.YouOwn+EffectSource | AffectedZone$ Command | AddAbility$ MoveToHand";
        String moveToHand = "ST$ ChangeZone | Cost$ 3 | Defined$ Self | Origin$ Command | Destination$ Hand | SorcerySpeed$ True | ActivationZone$ Command | SpellDescription$ Companion - Put CARDNAME in to your hand";

        StaticAbility stAb = StaticAbility.create(addToHandAbility, eff, eff.getCurrentState(), true);
        stAb.setSVar("MoveToHand", moveToHand);
        eff.addStaticAbility(stAb);

        return eff;
    }

    public static DetachedCardEffect createCommanderEffect(Game game, Card commander) {
        final String name = Lang.getInstance().getPossesive(commander.getName()) + " Commander Effect";
        DetachedCardEffect eff = new DetachedCardEffect(commander, name);

        if (game.getRules().hasAppliedVariant(GameType.Oathbreaker) && commander.getRules().canBeSignatureSpell()) {
            //signature spells can only reside on the stack or in the command zone
            String effStr = "DB$ ChangeZone | Origin$ Stack | Destination$ Command | Defined$ ReplacedCard";

            String moved = "Event$ Moved | ValidCard$ Card.EffectSource+YouOwn | Secondary$ True | Destination$ Graveyard,Exile,Hand,Library | " +
                    "Description$ If a signature spell would be put into another zone from the stack, put it into the command zone instead.";
            ReplacementEffect re = ReplacementHandler.parseReplacement(moved, eff, true);
            re.setOverridingAbility(AbilityFactory.getAbility(effStr, eff));
            eff.addReplacementEffect(re);

            //signature spells can only be cast if your oathbreaker is in on the battlefield under your control
            String castRestriction = "Mode$ CantBeCast | ValidCard$ Card.EffectSource+YouOwn | EffectZone$ Command | IsPresent$ Card.IsCommander+YouOwn+YouCtrl | PresentZone$ Battlefield | PresentCompare$ EQ0 | " +
                    "Description$ Signature spell can only be cast if your oathbreaker is on the battlefield under your control.";
            eff.addStaticAbility(castRestriction);
        }
        else {
            String effStr = "DB$ ChangeZone | Origin$ Battlefield,Graveyard,Exile,Library,Hand | Destination$ Command | Defined$ ReplacedCard";

            String moved = "Event$ Moved | ValidCard$ Card.EffectSource+YouOwn | Secondary$ True | Optional$ True | OptionalDecider$ You | CommanderMoveReplacement$ True ";
            if (game.getRules().hasAppliedVariant(GameType.TinyLeaders)) {
                moved += " | Destination$ Graveyard,Exile | Description$ If a commander would be put into its owner's graveyard or exile from anywhere, that player may put it into the command zone instead.";
            }
            else if (game.getRules().hasAppliedVariant(GameType.Oathbreaker)) {
                moved += " | Destination$ Graveyard,Exile,Hand,Library | Description$ If a commander would be exiled or put into hand, graveyard, or library from anywhere, that player may put it into the command zone instead.";
            } else {
            	// rule 903.9b
                moved += " | Destination$ Hand,Library | Description$ If a commander would be put into its owner's hand or library from anywhere, its owner may put it into the command zone instead.";
            }
            ReplacementEffect re = ReplacementHandler.parseReplacement(moved, eff, true);
            re.setOverridingAbility(AbilityFactory.getAbility(effStr, eff));
            eff.addReplacementEffect(re);
        }

        String mayBePlayedAbility = "Mode$ Continuous | EffectZone$ Command | MayPlay$ True | Affected$ Card.YouOwn+EffectSource | AffectedZone$ Command";
        if (game.getRules().hasAppliedVariant(GameType.Planeswalker)) { //support paying for Planeswalker with any color mana
            mayBePlayedAbility += " | MayPlayIgnoreColor$ True";
        }
        eff.addStaticAbility(mayBePlayedAbility);
        return eff;
    }

    public void changeOwnership(Card card) {
        // If lost then gained, just clear out of lost.
        // If gained then lost, just clear out of gained.
        Player oldOwner = card.getOwner();

        if (equals(oldOwner)) {
            return;
        }
        card.setOwner(this);

        if (lostOwnership.contains(card)) {
            lostOwnership.remove(card);
        } else {
            gainedOwnership.add(card);
        }

        if (oldOwner.gainedOwnership.contains(card)) {
            oldOwner.gainedOwnership.remove(card);
        } else {
            oldOwner.lostOwnership.add(card);
        }
    }

    public CardCollectionView getLostOwnership() {
        return lostOwnership;
    }
    public CardCollectionView getGainedOwnership() {
        return gainedOwnership;
    }

    @Override
    public PlayerView getView() {
        return view;
    }

    public SpellAbility getPaidForSA() {
        return paidForStack.peek();
    }
    public void pushPaidForSA(SpellAbility sa) {
        paidForStack.push(sa);
    }
    public void popPaidForSA() {
        // it could be empty if spell couldn't be cast
        paidForStack.poll();
    }
    public void clearPaidForSA() {
        paidForStack.clear();
    }

    public boolean isStartingPlayer() {
        return equals(game.getStartingPlayer());
    }

    public boolean isMonarch() {
        return equals(game.getMonarch());
    }

    public void createMonarchEffect(final String set) {
        final PlayerZone com = getZone(ZoneType.Command);
        if (monarchEffect == null) {
            monarchEffect = new Card(game.nextCardId(), null, game);
            monarchEffect.setOwner(this);
            monarchEffect.setImmutable(true);
            if (set != null) {
                monarchEffect.setImageKey("t:monarch_" + set.toLowerCase());
                monarchEffect.setSetCode(set);
            } else {
                monarchEffect.setImageKey("t:monarch");
            }
            monarchEffect.setName("The Monarch");

            {
                final String drawTrig = "Mode$ Phase | Phase$ End of Turn | TriggerZones$ Command | " +
                "ValidPlayer$ You |  TriggerDescription$ At the beginning of your end step, draw a card.";
                final String drawEff = "AB$ Draw | Cost$ 0 | Defined$ You";

                final Trigger drawTrigger = TriggerHandler.parseTrigger(drawTrig, monarchEffect, true);

                drawTrigger.setOverridingAbility(AbilityFactory.getAbility(drawEff, monarchEffect));
                monarchEffect.addTrigger(drawTrigger);
            }

            {
                final String damageTrig = "Mode$ DamageDone | ValidSource$ Creature | ValidTarget$ You | CombatDamage$ True | TriggerZones$ Command |" +
                " TriggerDescription$ Whenever a creature deals combat damage to you, its controller becomes the monarch.";
                final String damageEff = "AB$ BecomeMonarch | Cost$ 0 | Defined$ TriggeredSourceController";

                final Trigger damageTrigger = TriggerHandler.parseTrigger(damageTrig, monarchEffect, true);

                damageTrigger.setOverridingAbility(AbilityFactory.getAbility(damageEff, monarchEffect));
                monarchEffect.addTrigger(damageTrigger);
            }
            monarchEffect.updateStateForView();
        }
        com.add(monarchEffect);

        this.updateZoneForView(com);
    }
    public void removeMonarchEffect() {
        final PlayerZone com = getZone(ZoneType.Command);
        if (monarchEffect != null) {
            com.remove(monarchEffect);
            this.updateZoneForView(com);
        }
    }
    public boolean canBecomeMonarch() {
        return !StaticAbilityCantBecomeMonarch.anyCantBecomeMonarch(this);
    }

    public void createInitiativeEffect(final String set) {
        final PlayerZone com = getZone(ZoneType.Command);
        if (initiativeEffect == null) {
            initiativeEffect = new Card(game.nextCardId(), null, game);
            initiativeEffect.setOwner(this);
            initiativeEffect.setImmutable(true);
            if (set != null) {
                initiativeEffect.setImageKey("t:initiative_" + set.toLowerCase());
                initiativeEffect.setSetCode(set);
            } else {
                initiativeEffect.setImageKey("t:initiative");
            }
            initiativeEffect.setName("The Initiative");

            //Set up damage trigger
            final String damageTrig = "Mode$ DamageDoneOnceByController | ValidSource$ Player | ValidTarget$ You | " +
                    "CombatDamage$ True | TriggerZones$ Command | TriggerDescription$ Whenever one or more " +
                    "creatures a player controls deal combat damage to you, that player takes the initiative.";
            final String damageEff = "DB$ TakeInitiative | Defined$ TriggeredSource";

            final Trigger damageTrigger = TriggerHandler.parseTrigger(damageTrig, initiativeEffect, true);

            damageTrigger.setOverridingAbility(AbilityFactory.getAbility(damageEff, initiativeEffect));
            initiativeEffect.addTrigger(damageTrigger);

            //Set up triggers to venture into Undercity
            final String ventureTakeTrig  = "Mode$ TakesInitiative | ValidPlayer$ You | TriggerZones$ Command | " +
                    "TriggerDescription$ Whenever you take the initiative and at the beginning of your upkeep, " +
                    "venture into Undercity. (If you're in a dungeon, advance to the next room. If not, enter " +
                    "Undercity. You can take the initiative even if you already have it.)";

            final String ventureUpkpTrig = "Mode$ Phase | Phase$ Upkeep | TriggerZones$ Command | ValidPlayer$ You " +
                    "| TriggerDescription$ Whenever you take the initiative and at the beginning of your upkeep, " +
                    "venture into Undercity. (If you're in a dungeon, advance to the next room. If not, enter " +
                    "Undercity. You can take the initiative even if you already have it.) | Secondary$ True";

            final String ventureEff = "DB$ Venture | Dungeon$ Undercity";

            final Trigger ventureUTrigger = TriggerHandler.parseTrigger(ventureUpkpTrig, initiativeEffect, true);
            ventureUTrigger.setOverridingAbility(AbilityFactory.getAbility(ventureEff, initiativeEffect));
            initiativeEffect.addTrigger(ventureUTrigger);

            final Trigger ventureTTrigger = TriggerHandler.parseTrigger(ventureTakeTrig, initiativeEffect, true);
            ventureTTrigger.setOverridingAbility(AbilityFactory.getAbility(ventureEff, initiativeEffect));
            initiativeEffect.addTrigger(ventureTTrigger);

            initiativeEffect.updateStateForView();
        }

        final TriggerHandler triggerHandler = game.getTriggerHandler();
        triggerHandler.suppressMode(TriggerType.ChangesZone);
        game.getAction().moveTo(ZoneType.Command, initiativeEffect, null, null);
        triggerHandler.clearSuppression(TriggerType.ChangesZone);
        triggerHandler.clearActiveTriggers(initiativeEffect, null);
        triggerHandler.registerActiveTrigger(initiativeEffect, false);

        this.updateZoneForView(com);
    }

    public boolean hasInitiative() {
        return equals(game.getHasInitiative());
    }

    public void removeInitiativeEffect() {
        final PlayerZone com = getZone(ZoneType.Command);
        if (initiativeEffect != null) {
            com.remove(initiativeEffect);
            this.updateZoneForView(com);
        }
    }

    public void updateKeywordCardAbilityText() {
        if (getKeywordCard() == null)
            return;
        final PlayerZone com = getZone(ZoneType.Command);
        keywordEffect.setText("");
        keywordEffect.updateAbilityTextForView();
        boolean headerAdded = false;
        StringBuilder kw = new StringBuilder();
        for (KeywordInterface k : keywords) {
            if (!headerAdded) {
                headerAdded = true;
                kw.append(this.getName()).append(" has: \n");
            }
            kw.append(k).append("\n");
        }
        if (!kw.toString().isEmpty()) {
            keywordEffect.setText(trimKeywords(kw.toString()));
            keywordEffect.updateAbilityTextForView();
        }
        this.updateZoneForView(com);
    }
    public String trimKeywords(String keywordTexts) {
        if (keywordTexts.contains("Protection:")) {
            List <String> lines = Arrays.asList(keywordTexts.split("\n"));
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("Protection:")) {
                    List<String> parts = Arrays.asList(lines.get(i).split(":"));
                    if (parts.size() > 2) {
                        keywordTexts = TextUtil.fastReplace(keywordTexts, lines.get(i), parts.get(2));
                    }
                }
            }
        }
        keywordTexts = TextUtil.fastReplace(keywordTexts,":Card.named", " from ");
        keywordTexts = TextUtil.fastReplace(keywordTexts, ":Card.Black:", " from ");
        keywordTexts = TextUtil.fastReplace(keywordTexts, ":Card.Blue:", " from ");
        keywordTexts = TextUtil.fastReplace(keywordTexts, ":Card.Red:", " from ");
        keywordTexts = TextUtil.fastReplace(keywordTexts, ":Card.Green:", " from ");
        keywordTexts = TextUtil.fastReplace(keywordTexts, ":Card.White:", " from ");
        keywordTexts = TextUtil.fastReplace(keywordTexts, ":Card.MonoColor:", " from ");
        keywordTexts = TextUtil.fastReplace(keywordTexts, ":Card.MultiColor:", " from ");
        keywordTexts = TextUtil.fastReplace(keywordTexts, ":Card.Colorless:", " from ");
        return keywordTexts;
    }
    public void checkKeywordCard() {
        if (keywordEffect == null)
            return;
        final PlayerZone com = getZone(ZoneType.Command);
        if (keywordEffect.getAbilityText().isEmpty()) {
            com.remove(keywordEffect);
            this.updateZoneForView(com);
            keywordEffect = null;
        }
    }

    public boolean hasBlessing() {
        return blessingEffect != null;
    }
    public void setBlessing(boolean bless) {
        // no need to to change
        if ((blessingEffect != null) == bless) {
            return;
        }

        final PlayerZone com = getZone(ZoneType.Command);

        if (bless) {
            blessingEffect = new Card(game.nextCardId(), null, game);
            blessingEffect.setOwner(this);
            blessingEffect.setImageKey("t:blessing");
            blessingEffect.setName("City's Blessing");
            blessingEffect.setImmutable(true);

            blessingEffect.updateStateForView();

            com.add(blessingEffect);
        } else {
            com.remove(blessingEffect);
            blessingEffect = null;
        }

        this.updateZoneForView(com);
    }

    public final boolean sameTeam(final Player other) {
        if (this.equals(other)) {
            return true;
        }
        if (this.teamNumber < 0 || other.getTeam() < 0) {
            return false;
        }
        return this.teamNumber == other.getTeam();
    }

    public final int countExaltedBonus() {
        return CardLists.getAmountOfKeyword(this.getCardsIn(ZoneType.Battlefield), Keyword.EXALTED);
    }

    public final boolean isCursed() {
        return CardLists.count(getAttachedCards(), CardPredicates.Presets.CURSE) > 0;
    }

    public boolean canDiscardBy(SpellAbility sa, final boolean effect) {
        if (sa == null) {
            return true;
        }

        return !StaticAbilityCantDiscard.cantDiscard(this, sa, effect);
    }

    public boolean canSearchLibraryWith(SpellAbility sa, Player targetPlayer) {
        if (sa == null) {
            return true;
        }

        if (hasKeyword("CantSearchLibrary")) {
            return false;
        }
        return targetPlayer == null || !targetPlayer.equals(sa.getActivatingPlayer())
 || !hasKeyword("Spells and abilities you control can't cause you to search your library.");
    }

    public Card getKeywordCard() {
        if (keywordEffect != null) {
            return keywordEffect;
        }

        final PlayerZone com = getZone(ZoneType.Command);

        keywordEffect = new Card(game.nextCardId(), null, game);
        keywordEffect.setImmutable(true);
        keywordEffect.setOwner(this);
        keywordEffect.setName("Keyword Effects");
        keywordEffect.setImageKey(ImageKeys.HIDDEN_CARD);

        keywordEffect.updateStateForView();

        com.add(keywordEffect);

        this.updateZoneForView(com);
        return keywordEffect;
    }

    public void addAdditionalVote(long timestamp, int value) {
        additionalVotes.put(timestamp, value);
        getView().updateAdditionalVote(this);
        getGame().fireEvent(new GameEventPlayerStatsChanged(this, false));
    }
    public void removeAdditionalVote(long timestamp) {
        if (additionalVotes.remove(timestamp) != null) {
            getView().updateAdditionalVote(this);
            getGame().fireEvent(new GameEventPlayerStatsChanged(this, false));
        }
    }

    public int getAdditionalVotesAmount() {
        int value = 0;
        for (Integer i : additionalVotes.values()) {
            value += i;
        }
        return value;
    }

    public void addAdditionalOptionalVote(long timestamp, int value) {
        additionalOptionalVotes.put(timestamp, value);
        getView().updateOptionalAdditionalVote(this);
        getGame().fireEvent(new GameEventPlayerStatsChanged(this, false));
    }
    public void removeAdditionalOptionalVote(long timestamp) {
        if (additionalOptionalVotes.remove(timestamp) != null) {
            getView().updateOptionalAdditionalVote(this);
            getGame().fireEvent(new GameEventPlayerStatsChanged(this, false));
        }
    }

    public int getAdditionalOptionalVotesAmount() {
        int value = 0;
        for (Integer i : additionalOptionalVotes.values()) {
            value += i;
        }
        return value;
    }

    public boolean addControlVote(long timestamp) {
        if (controlVotes.add(timestamp)) {
            updateControlVote();
            return true;
        }
        return false;
    }
    public boolean removeControlVote(long timestamp) {
        if (controlVotes.remove(timestamp)) {
            updateControlVote();
            return true;
        }
        return false;
    }

    void updateControlVote() {
        // need to update all players because it can't know
        Player control = getGame().getControlVote();
        for (Player pl : getGame().getPlayers()) {
            pl.getView().updateControlVote(pl.equals(control));
            getGame().fireEvent(new GameEventPlayerStatsChanged(pl, false));
        }
    }

    public Set<Long> getControlVote() {
        return controlVotes;
    }
    public void setControlVote(Set<Long> value) {
        controlVotes.clear();
        controlVotes.addAll(value);
        updateControlVote();
    }

    public Long getHighestControlVote() {
        if (controlVotes.isEmpty()) {
            return null;
        }
        return controlVotes.last();
    }

    public void addCycled(SpellAbility sp) {
        cycledThisTurn++;

        Map<AbilityKey, Object> cycleParams = AbilityKey.mapFromCard(CardUtil.getLKICopy(game.getCardState(sp.getHostCard())));
        cycleParams.put(AbilityKey.Cause, sp);
        cycleParams.put(AbilityKey.Player, this);
        cycleParams.put(AbilityKey.NumThisTurn, cycledThisTurn);
        game.getTriggerHandler().runTrigger(TriggerType.Cycled, cycleParams, false);
    }

    public int getCycledThisTurn() {
        return cycledThisTurn;
    }

    public void resetCycledThisTurn() {
        cycledThisTurn = 0;
    }

    public void addEquipped() { equippedThisTurn++; }

    public int getEquippedThisTurn() {
        return equippedThisTurn;
    }

    public void resetEquippedThisTurn() {
        equippedThisTurn = 0;
    }

    public boolean hasUrzaLands() {
        final CardCollectionView landsControlled = getCardsIn(ZoneType.Battlefield);
        return Iterables.any(landsControlled, Predicates.and(CardPredicates.isType("Urza's"), CardPredicates.isType("Mine")))
                && Iterables.any(landsControlled, Predicates.and(CardPredicates.isType("Urza's"), CardPredicates.isType("Power-Plant")))
                && Iterables.any(landsControlled, Predicates.and(CardPredicates.isType("Urza's"), CardPredicates.isType("Tower")));
    }

    public void revealFaceDownCards() {
        final List<List<ZoneType>> revealZones = Arrays.asList(Arrays.asList(ZoneType.Battlefield, ZoneType.Merged), Arrays.asList(ZoneType.Exile));
        final PlayerCollection otherPlayers = new PlayerCollection(game.getRegisteredPlayers());
        otherPlayers.remove(this);

        for (List<ZoneType> z : revealZones) {
            CardCollection revealCards = new CardCollection();
            for (Card c : game.getCardsInOwnedBy(z, this)) {
                if (!c.isRealFaceDown()) continue;

                Card lki = CardUtil.getLKICopy(c);
                lki.forceTurnFaceUp();
                lki.setZone(c.getZone());
                revealCards.add(lki);
            }
            game.getAction().revealTo(revealCards, otherPlayers, Localizer.getInstance().getMessage("lblRevealFaceDownCards"));
        }
    }

    public void learnLesson(SpellAbility sa, CardZoneTable table, Map<AbilityKey, Object> params) {
        if (hasLost()) {
            return;
        }
        // Replacement effects
        Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(this);
        repParams.put(AbilityKey.Cause, sa);
        if (params != null) {
            repParams.putAll(params);
        }
        if (game.getReplacementHandler().run(ReplacementType.Learn, repParams) != ReplacementResult.NotReplaced) {
            return;
        }

        CardCollection list = new CardCollection();
        if (!isControlled()) {
            list.addAll(CardLists.getType(getZone(ZoneType.Sideboard), "Lesson"));
        }
        list.addAll(getZone(ZoneType.Hand));
        if (list.isEmpty()) {
            return;
        }

        Card c = getController().chooseSingleCardForZoneChange(ZoneType.Hand, ImmutableList.of(ZoneType.Sideboard, ZoneType.Hand),
                sa, list, null, Localizer.getInstance().getMessage("lblLearnALesson"), true, this);
        if (c == null) {
            return;
        }
        if (c.isInZone(ZoneType.Sideboard)) { // Sideboard Lesson to Hand
            game.getAction().reveal(new CardCollection(c), c.getOwner(), true);
            Card moved = game.getAction().moveTo(ZoneType.Hand, c, sa, params);
            table.put(ZoneType.Sideboard, ZoneType.Hand, moved);
        } else if (c.isInZone(ZoneType.Hand)) { // Discard and Draw
            boolean firstDiscard = getNumDiscardedThisTurn() == 0;
            if (discard(c, sa, true, table, params) != null) {
                // Change this if something would make multiple player learn at the same time

                // Discard Trigger outside Effect
                final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(this);
                runParams.put(AbilityKey.Cards, new CardCollection(c));
                runParams.put(AbilityKey.Cause, sa);
                runParams.put(AbilityKey.FirstTime, firstDiscard);
                if (params != null) {
                    runParams.putAll(params);
                }
                getGame().getTriggerHandler().runTrigger(TriggerType.DiscardedAll, runParams, false);

                for (Card d : drawCards(1, sa, params)) {
                    table.put(ZoneType.Library, ZoneType.Hand, d); // does a ChangesZoneAll care about moving from Library to Hand
                }
            }
        }
    }
}
