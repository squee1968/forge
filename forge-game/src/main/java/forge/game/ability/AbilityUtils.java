package forge.game.ability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.*;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.base.Predicate;

import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.card.mana.ManaCostShard;
import forge.game.CardTraitBase;
import forge.game.Direction;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.AbilityFactory.AbilityRecordType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardFactoryUtil;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardState;
import forge.game.card.CardUtil;
import forge.game.card.CounterType;
import forge.game.cost.Cost;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.LandAbility;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityRestriction;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellPermanent;
import forge.game.spellability.TargetChoices;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.Expressions;
import forge.util.MyRandom;
import forge.util.TextUtil;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;


public class AbilityUtils {
    private final static ImmutableList<String> cmpList = ImmutableList.of("LT", "LE", "EQ", "GE", "GT", "NE");

    static public final String WITH_DELIMITER = "((?<=%1$s)|(?=%1$s))";

    // should the three getDefined functions be merged into one? Or better to
    // have separate?
    // If we only have one, each function needs to Cast the Object to the
    // appropriate type when using
    // But then we only need update one function at a time once the casting is
    // everywhere.
    // Probably will move to One function solution sometime in the future
    public static CardCollection getDefinedCards(final Card hostCard, final String def, final CardTraitBase sa) {
        CardCollection cards = new CardCollection();
        String changedDef = (def == null) ? "Self" : applyAbilityTextChangeEffects(def, sa); // default to Self
        final String[] incR = changedDef.split("\\.", 2);
        String defined = incR[0];
        final Game game = hostCard.getGame();

        Card c = null;

        if (defined.equals("Self")) {
            c = hostCard;
        } else if (defined.equals("CorrectedSelf")) {
            c = game.getCardState(hostCard);
        }
        else if (defined.equals("OriginalHost")) {
            if (sa instanceof SpellAbility) {
                c = ((SpellAbility)sa).getRootAbility().getOriginalHost();
            } else {
                c = sa.getOriginalHost();
            }
        }
        else if (defined.equals("EffectSource")) {
            if (hostCard.isImmutable()) {
                c = findEffectRoot(hostCard);
            }
        }
        else if (defined.equals("Equipped")) {
            c = hostCard.getEquipping();
        }
        else if (defined.startsWith("AttachedTo ")) {
            String v = defined.split(" ")[1];
            for (GameEntity ge : getDefinedEntities(hostCard, v, sa)) {
                // TODO handle phased out inside attachedCards
                Iterables.addAll(cards, ge.getAttachedCards());
            }
        }
        else if (defined.startsWith("AttachedBy ")) {
            String v = defined.split(" ")[1];
            for (Card attachment : getDefinedCards(hostCard, v, sa)) {
                Card attached = attachment.getAttachedTo();
                if (attached != null) {
                    cards.add(attached);
                }
            }
        }
        else if (defined.equals("Enchanted")) {
            c = hostCard.getEnchantingCard();
            if (c == null && sa instanceof SpellAbility) {
                SpellAbility root = ((SpellAbility)sa).getRootAbility();
                CardCollection sacrificed = root.getPaidList("Sacrificed");
                if (sacrificed != null && !sacrificed.isEmpty()) {
                    c = sacrificed.getFirst().getEnchantingCard();
                }
            }
        } else if (defined.equals("TopOfGraveyard")) {
            final CardCollectionView grave = hostCard.getController().getCardsIn(ZoneType.Graveyard);

            if (grave.size() > 0) {
                c = grave.getLast();
            } else {
                // we don't want this to fall through and return the "Self"
                return cards;
            }
        }
        else if (defined.endsWith("OfLibrary")) {
            final CardCollectionView lib = hostCard.getController().getCardsIn(ZoneType.Library);
            int libSize = lib.size();
            if (libSize > 0) { // TopOfLibrary or BottomOfLibrary
                if (defined.startsWith("TopThird")) {
                    int third = defined.contains("RoundedDown") ? (int) Math.floor(libSize / 3.0)
                            : (int) Math.ceil(libSize / 3.0);
                    for (int i=0; i<third; i++) {
                        cards.add(lib.get(i));
                    }
                } else {
                    c = lib.get(defined.startsWith("Top") ? 0 : libSize - 1);
                }
            } else {
                // we don't want this to fall through and return the "Self"
                return cards;
            }
        }
        else if ((defined.equals("Targeted") || defined.equals("TargetedCard")) && sa instanceof SpellAbility) {
            for (TargetChoices tc : ((SpellAbility)sa).getAllTargetChoices()) {
                Iterables.addAll(cards, tc.getTargetCards());
            }
        }
        else if (defined.equals("TargetedSource") && sa instanceof SpellAbility) {
            for (TargetChoices tc : ((SpellAbility)sa).getAllTargetChoices()) for (SpellAbility s : tc.getTargetSpells()) {
                    cards.add(s.getHostCard());
                }
        }
        else if (defined.equals("ThisTargetedCard") && sa instanceof SpellAbility) { // do not add parent targeted
            if (((SpellAbility)sa).getTargets() != null) {
                Iterables.addAll(cards, ((SpellAbility)sa).getTargets().getTargetCards());
            }
        }
        else if (defined.equals("ParentTarget") && sa instanceof SpellAbility) {
            final SpellAbility parent = ((SpellAbility)sa).getParentTargetingCard();
            if (parent != null) {
                Iterables.addAll(cards, parent.getTargets().getTargetCards());
            }
        }
        else if (defined.startsWith("Triggered") && sa instanceof SpellAbility) {
            final SpellAbility root = ((SpellAbility)sa).getRootAbility();
            if (defined.contains("LKICopy")) { //Triggered*LKICopy
                int lkiPosition = defined.indexOf("LKICopy");
                AbilityKey type = AbilityKey.fromString(defined.substring(9, lkiPosition));
                final Object crd = root.getTriggeringObject(type);
                if (crd instanceof Card) {
                    c = (Card) crd;
                } else if (crd instanceof Iterable) {
                    cards.addAll(Iterables.filter((Iterable<?>) crd, Card.class));
                }
            }
            else if (defined.contains("HostCard")) { //Triggered*HostCard
                int hcPosition = defined.indexOf("HostCard");
                AbilityKey type = AbilityKey.fromString(defined.substring(9, hcPosition));
                final Object o = root.getTriggeringObject(type);
                if (o instanceof SpellAbility) {
                    c = ((SpellAbility) o).getHostCard();
                }
            } else {
                AbilityKey type = AbilityKey.fromString(defined.substring(9));
                final Object crd = root.getTriggeringObject(type);
                if (crd instanceof Card) {
                    c = game.getCardState((Card) crd);
                } else if (crd instanceof Iterable) {
                    cards.addAll(Iterables.filter((Iterable<?>) crd, Card.class));
                }
            }
        }
        else if (defined.startsWith("Replaced") && sa instanceof SpellAbility) {
            final SpellAbility root = ((SpellAbility)sa).getRootAbility();
            AbilityKey type = AbilityKey.fromString(defined.substring(8));
            final Object crd = root.getReplacingObject(type);

            if (crd instanceof Card) {
                c = (Card) crd;
            } else if (crd instanceof Iterable<?>) {
                cards.addAll(Iterables.filter((Iterable<?>) crd, Card.class));
            }
        }
        else if (defined.equals("Remembered") || defined.equals("RememberedCard")) {
            if (!hostCard.hasRemembered()) {
                final Card newCard = game.getCardState(hostCard);
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Card) {
                        cards.add(game.getCardState((Card) o));
                    }
                }
            }
            // game.getCardState(Card c) is not working for LKI
            for (final Object o : hostCard.getRemembered()) {
                if (o instanceof Card) {
                    cards.addAll(addRememberedFromCardState(game, (Card)o));
                }
            }
        } else if (defined.equals("RememberedLKI")) {
            for (final Object o : hostCard.getRemembered()) {
                if (o instanceof Card) {
                    cards.add((Card) o);
                }
            }
        } else if (defined.equals("DirectRemembered")) {
            if (!hostCard.hasRemembered()) {
                final Card newCard = game.getCardState(hostCard);
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Card) {
                        cards.add((Card) o);
                    }
                }
            }

            for (final Object o : hostCard.getRemembered()) {
                if (o instanceof Card) {
                    cards.add((Card) o);
                }
            }
        } else if (defined.equals("DelayTriggerRememberedLKI") && sa instanceof SpellAbility) {
            SpellAbility root = ((SpellAbility)sa).getRootAbility();
            if (root != null) {
                for (Object o : root.getTriggerRemembered()) {
                    if (o instanceof Card) {
                        cards.add((Card)o);
                    }
                }
            } else {
                System.err.println("Warning: couldn't find trigger SA in the chain of SpellAbility " + sa);
            }
        } else if (defined.equals("DelayTriggerRemembered") && sa instanceof SpellAbility) {
            SpellAbility root = ((SpellAbility)sa).getRootAbility();
            if (root != null) {
                for (Object o : root.getTriggerRemembered()) {
                    if (o instanceof Card) {
                        cards.addAll(addRememberedFromCardState(game, (Card)o));
                    }
                }
            } else {
                System.err.println("Warning: couldn't find trigger SA in the chain of SpellAbility " + sa);
            }
        } else if (defined.equals("FirstRemembered")) {
            Object o = hostCard.getFirstRemembered();
            if (o instanceof Card) {
                cards.add(game.getCardState((Card) o));
            }
        } else if (defined.equals("LastRemembered")) {
            Object o = Iterables.getLast(hostCard.getRemembered(), null);
            if (o instanceof Card) {
                cards.add(game.getCardState((Card) o));
            }
        } else if (defined.equals("ImprintedLKI")) {
            for (final Card imprint : hostCard.getImprintedCards()) {
                cards.add(imprint);
            }
        } else if (defined.equals("Imprinted")) {
            for (final Card imprint : hostCard.getImprintedCards()) {
                cards.add(game.getCardState(imprint));
            }
        } else if (defined.startsWith("ThisTurnEntered")) {
            final String[] workingCopy = defined.split("_");
            ZoneType destination, origin;
            String validFilter;

            destination = ZoneType.smartValueOf(workingCopy[1]);
            if (workingCopy[2].equals("from")) {
                origin = ZoneType.smartValueOf(workingCopy[3]);
                validFilter = workingCopy[4];
            } else {
                origin = null;
                validFilter = workingCopy[2];
            }
            for (final Card cl : CardUtil.getThisTurnEntered(destination, origin, validFilter, hostCard, sa)) {
                Card gameState = game.getCardState(cl, null);
                // cards that use this should only care about if it is still in that zone
                // TODO if all LKI needs to be returned, need to change CardCollection return from this function
                if (gameState != null && gameState.equalsWithTimestamp(cl)) {
                    cards.add(gameState);
                }
            }
        } else if (defined.equals("ChosenCard")) {
            for (final Card chosen : hostCard.getChosenCards()) {
                cards.add(game.getCardState(chosen));
            }
        } else if (defined.startsWith("CardUID_")) {
            String idString = defined.substring(8);
            for (final Card cardByID : game.getCardsInGame()) {
                if (cardByID.getId() == Integer.valueOf(idString)) {
                    cards.add(game.getCardState(cardByID));
                }
            }
        } else if (defined.startsWith("Valid")) {
            Iterable<Card> candidates;
            String validDefined;
            if (defined.startsWith("Valid ")) {
                candidates = game.getCardsIn(ZoneType.Battlefield);
                validDefined = changedDef.substring("Valid ".length());
            } else if (defined.startsWith("ValidAll ")) {
                candidates = game.getCardsInGame();
                validDefined = changedDef.substring("ValidAll ".length());
            } else {
                String[] s = changedDef.split(" ", 2);
                String zone = s[0].substring("Valid".length());
                candidates = game.getCardsIn(ZoneType.smartValueOf(zone));
                validDefined = s[1];
            }
            cards.addAll(CardLists.getValidCards(candidates, validDefined, hostCard.getController(), hostCard, sa));
            return cards;
        } else {
            CardCollection list = getPaidCards(sa, defined);
            if (list != null) {
                cards.addAll(list);
            }
        }

        if (c != null) {
            cards.add(c);
        }

        if (incR.length > 1 && !cards.isEmpty()) {
            String[] valids = incR[1].split(",");
            // need to add valids onto all of them
            for (int i = 0; i < valids.length; i++) {
                valids[i] = "Card." + valids[i];
            }
            cards = CardLists.getValidCards(cards, valids, hostCard.getController(), hostCard, sa);
        }

        return cards;
    }

    private static CardCollection addRememberedFromCardState(Game game, Card c) {
        CardCollection coll = new CardCollection();
        Card newState = game.getCardState(c);
        if (c.getMeldedWith() != null) {
            // When remembering a card that flickers, also remember it's meld pair
            coll.add(game.getCardState(c.getMeldedWith()));
        }
        coll.add(newState);
        return coll;
    }

    private static Card findEffectRoot(Card startCard) {
        Card cc = startCard.getEffectSource();
        if (cc != null) {
            if (cc.isImmutable()) {
                return findEffectRoot(cc);
            }
            return cc;
        }
        return null; //If this happens there is a card in the game that is not in any zone
    }

    // Utility functions used by the AFs
    /**
     * <p>
     * calculateAmount.
     * </p>
     *
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param amount
     *            a {@link java.lang.String} object.
     * @param ability
     *            a {@link forge.game.CardTraitBase} object.
     * @return a int.
     */
    public static int calculateAmount(final Card card, String amount, final CardTraitBase ability) {
        return calculateAmount(card, amount, ability, false);
    }
    public static int calculateAmount(final Card card, String amount, final CardTraitBase ability, boolean maxto) {
        // return empty strings and constants
        if (StringUtils.isBlank(amount)) { return 0; }
        if (card == null) { return 0; }
        final Player player = card.getController();
        final Game game = player == null ? card.getGame() : player.getGame();

        // Strip and save sign for calculations
        final boolean startsWithPlus = amount.charAt(0) == '+';
        final boolean startsWithMinus = amount.charAt(0) == '-';
        if (startsWithPlus || startsWithMinus) { amount = amount.substring(1); }
        int multiplier = startsWithMinus ? -1 : 1;

        // return result soon for plain numbers
        if (StringUtils.isNumeric(amount)) {
            int val = Integer.parseInt(amount);
            if (maxto) {
                val = Math.max(val, 0);
            }
            return val * multiplier;
        }

        // Try to fetch variable, try ability first, then card.
        String svarval = null;
        if (amount.indexOf('$') > 0) { // when there is a dollar sign, it's not a reference, it's a raw value!
            svarval = amount;
        }
        else if (ability != null) {
            svarval = ability.getSVar(amount);
        }
        if (StringUtils.isBlank(svarval)) {
            if ((ability != null) && (ability instanceof SpellAbility) && !(ability instanceof SpellPermanent)) {
                System.err.printf("SVar '%s' not found in ability, fallback to Card (%s). Ability is (%s)%n", amount, card.getName(), ability);
            }
            svarval = card.getSVar(amount);
        }

        if (StringUtils.isBlank(svarval)) {
            // cost hasn't been paid yet
            if (amount.startsWith("Cost")) {
                return 0;
            }
            // Nothing to do here if value is missing or blank
            System.err.printf("SVar '%s' not defined in Card (%s)%n", amount, card.getName());
            return 0;
        }

        // Handle numeric constant coming in svar value
        if (StringUtils.isNumeric(svarval)) {
            int val = Integer.parseInt(svarval);
            if (maxto) {
                val = Math.max(val, 0);
            }
            return val * multiplier;
        }

        // Parse Object$Property string
        final String[] calcX = svarval.split("\\$", 2);

        // Incorrect parses mean zero.
        if (calcX.length == 1 || calcX[1].equals("none")) {
            return 0;
        }

        // modify amount string for text changes
        calcX[1] = applyAbilityTextChangeEffects(calcX[1], ability);

        Integer val = null;
        if (calcX[0].startsWith("Count")) {
            val = xCount(card, calcX[1], ability);
        } else if (calcX[0].startsWith("Number")) {
            val = xCount(card, svarval, ability);
        } else if (calcX[0].startsWith("SVar")) {
            final String[] l = calcX[1].split("/");
            final String m = CardFactoryUtil.extractOperators(calcX[1]);
            val = doXMath(calculateAmount(card, l[0], ability), m, card, ability);
        } else if (calcX[0].startsWith("PlayerCount")) {
            final String hType = calcX[0].substring(11);
            final FCollection<Player> players = new FCollection<>();
            if (hType.equals("Players") || hType.equals("")) {
                players.addAll(game.getPlayers());
                val = playerXCount(players, calcX[1], card, ability);
            } else if (hType.equals("YourTeam")) {
                players.addAll(player.getYourTeam());
                val = playerXCount(players, calcX[1], card, ability);
            } else if (hType.equals("Opponents")) {
                players.addAll(player.getOpponents());
                val = playerXCount(players, calcX[1], card, ability);
            } else if (hType.equals("RegisteredOpponents")) {
                players.addAll(Iterables.filter(game.getRegisteredPlayers(), PlayerPredicates.isOpponentOf(player)));
                val = playerXCount(players, calcX[1], card, ability);
            } else if (hType.equals("Other")) {
                players.addAll(player.getAllOtherPlayers());
                val = playerXCount(players, calcX[1], card, ability);
            } else if (hType.startsWith("Remembered")) {
                addPlayer(card.getRemembered(), hType, players);
                val = playerXCount(players, calcX[1], card, ability);
            } else if (hType.equals("NonActive")) {
                players.addAll(game.getPlayers());
                players.remove(game.getPhaseHandler().getPlayerTurn());
                val = playerXCount(players, calcX[1], card, ability);
            } else if (hType.startsWith("PropertyYou")) {
                if (ability instanceof SpellAbility) {
                    // Hollow One
                    players.add(((SpellAbility) ability).getActivatingPlayer());
                } else {
                    players.add(player);
                }
                val = playerXCount(players, calcX[1], card, ability);
            } else if (hType.startsWith("Property")) {
                String defined = hType.split("Property")[1];
                for (Player p : game.getPlayersInTurnOrder()) {
                    if (ability instanceof SpellAbility && p.hasProperty(defined, ((SpellAbility) ability).getActivatingPlayer(), ability.getHostCard(), ability)) {
                        players.add(p);
                    } else if (!(ability instanceof SpellAbility) && p.hasProperty(defined, player, ability.getHostCard(), ability)) {
                        players.add(p);
                    }
                }
                val = playerXCount(players, calcX[1], card, ability);
            } else if (hType.startsWith("Defined")) {
                String defined = hType.split("Defined")[1];
                val = playerXCount(getDefinedPlayers(card, defined, ability), calcX[1], card, ability);
            } else {
                val = 0;
            }
        } else if (calcX[0].equals("OriginalHost")) {
            val = xCount(ability.getOriginalHost(), calcX[1], ability);
        } else if (calcX[0].equals("LastStateBattlefield") && ability instanceof SpellAbility) {
            Card c = ((SpellAbility) ability).getLastStateBattlefield().get(card);
            val = c == null ? 0 : xCount(c, calcX[1], ability);
        } else if (calcX[0].startsWith("Remembered")) {
            // Add whole Remembered list to handlePaid
            final CardCollection list = new CardCollection();
            Card newCard = card;
            if (!card.hasRemembered()) {
                newCard = game.getCardState(card);
            }

            if (calcX[0].endsWith("LKI")) { // last known information
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Card) {
                        list.add((Card) o);
                    }
                }
            }
            else {
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Card) {
                        list.add(game.getCardState((Card) o));
                    }
                }
            }

            val = handlePaid(list, calcX[1], card, ability);
        }
        else if (calcX[0].startsWith("Imprinted")) {
            // Add whole Imprinted list to handlePaid
            final CardCollection list = new CardCollection();
            Card newCard = card;
            if (card.getImprintedCards().isEmpty()) {
                newCard = game.getCardState(card);
            }

            if (calcX[0].endsWith("LKI")) { // last known information
                list.addAll(newCard.getImprintedCards());
            }
            else {
                for (final Card c : newCard.getImprintedCards()) {
                    list.add(game.getCardState(c));
                }
            }

            val = handlePaid(list, calcX[1], card, ability);
        }
        else if (calcX[0].matches("Enchanted") || calcX[0].matches("Equipped")) {
            // Add whole Enchanted list to handlePaid
            final CardCollection list = new CardCollection();
            if (card.isEnchanting()) {
                Object o = card.getEntityAttachedTo();
                if (o instanceof Card) {
                    list.add(game.getCardState((Card) o));
                }
            }
            val = handlePaid(list, calcX[1], card, ability);
        }

        // All the following only work for SpellAbilities
        else if (ability instanceof SpellAbility) {
            final SpellAbility sa = (SpellAbility) ability;
            if (calcX[0].startsWith("Modes")) {
                int chosenModes = 0;
                SpellAbility sub = sa;
                while(sub != null) {
                    if (!sub.getSVar("CharmOrder").equals("")) {
                        chosenModes++;
                    }
                    sub = sub.getSubAbility();
                }
                // Count Math
                final String m = CardFactoryUtil.extractOperators(calcX[1]);
                val = doXMath(chosenModes, m, card, ability);
            }
            // Player attribute counting
            else if (calcX[0].startsWith("TargetedPlayer")) {
                final List<Player> players = new ArrayList<>();
                final SpellAbility saTargeting = sa.getSATargetingPlayer();
                if (null != saTargeting) {
                    Iterables.addAll(players, saTargeting.getTargets().getTargetPlayers());
                }
                val = playerXCount(players, calcX[1], card, ability);
            }
            else if (calcX[0].startsWith("ThisTargetedPlayer")) {
                final List<Player> players = new ArrayList<>();
                Iterables.addAll(players, sa.getTargets().getTargetPlayers());
                val = playerXCount(players, calcX[1], card, ability);
            }
            else if (calcX[0].startsWith("TargetedObjects")) {
                List<GameObject> objects = new ArrayList<>();
                // Make list of all targeted objects starting with the root SpellAbility
                SpellAbility loopSA = sa.getRootAbility();
                while (loopSA != null) {
                    if (loopSA.usesTargeting()) {
                        Iterables.addAll(objects, loopSA.getTargets());
                    }
                    loopSA = loopSA.getSubAbility();
                }
                if (calcX[0].endsWith("Distinct")) {
                    objects = new ArrayList<>(new HashSet<>(objects));
                }
                val = objectXCount(objects, calcX[1], card, ability);
            }
            else if (calcX[0].startsWith("TargetedController")) {
                final PlayerCollection players = new PlayerCollection();
                final CardCollection list = getDefinedCards(card, "Targeted", sa);
                final List<SpellAbility> sas = getDefinedSpellAbilities(card, "Targeted", sa);

                for (final Card c : list) {
                    players.add(c.getController());
                }
                for (final SpellAbility s : sas) {
                    players.add(s.getHostCard().getController());
                }
                val = playerXCount(players, calcX[1], card, ability);
            }
            else if (calcX[0].startsWith("TargetedByTarget")) {
                final CardCollection tgtList = new CardCollection();
                final List<SpellAbility> saList = getDefinedSpellAbilities(card, "Targeted", sa);

                for (final SpellAbility s : saList) {
                    tgtList.addAll(getDefinedCards(s.getHostCard(), "Targeted", s));
                }
                val = handlePaid(tgtList, calcX[1], card, ability);
            }
            else if (calcX[0].startsWith("TriggeredPlayers") || calcX[0].equals("TriggeredCardController")) {
                String key = calcX[0];
                if (calcX[0].startsWith("TriggeredPlayers")) {
                    key = "Triggered" + key.substring(16);
                }
                val = playerXCount(getDefinedPlayers(card, key, sa), calcX[1], card, ability);
            }
            else if (calcX[0].startsWith("TriggeredPlayer") || calcX[0].startsWith("TriggeredTarget")
                    || calcX[0].startsWith("TriggeredDefendingPlayer")) {
                final SpellAbility root = sa.getRootAbility();
                Object o = root.getTriggeringObject(AbilityKey.fromString(calcX[0].substring(9)));
                val = o instanceof Player ? playerXProperty((Player) o, calcX[1], card, ability) : 0;
            }
            else if (calcX[0].equals("TriggeredSpellAbility") || calcX[0].equals("TriggeredStackInstance") || calcX[0].equals("SpellTargeted")) {
                final SpellAbility sat = Iterables.getFirst(getDefinedSpellAbilities(card, calcX[0], sa), null);
                val = sat == null ? 0 : xCount(sat.getHostCard(), calcX[1], sat);
            }
            else if (calcX[0].startsWith("TriggerCount")) {
                // TriggerCount is similar to a regular Count, but just
                // pulls Integer Values from Trigger objects
                final SpellAbility root = sa.getRootAbility();
                final String[] l = calcX[1].split("/");
                final String m = CardFactoryUtil.extractOperators(calcX[1]);
                Integer count = null;
                if (calcX[0].endsWith("Max")) {
                    @SuppressWarnings("unchecked")
                    Iterable<Integer> numbers = (Iterable<Integer>) root.getTriggeringObject(AbilityKey.fromString(l[0]));
                    for (Integer n : numbers) {
                        if (count == null || n > count) {
                            count = n;
                        }
                    }
                } else {
                    count = (Integer) root.getTriggeringObject(AbilityKey.fromString(l[0]));
                }

                val = doXMath(ObjectUtils.firstNonNull(count, 0), m, card, ability);
            }
            else if (calcX[0].startsWith("ReplaceCount")) {
                // ReplaceCount is similar to a regular Count, but just
                // pulls Integer Values from Replacement objects
                final SpellAbility root = sa.getRootAbility();
                final String[] l = calcX[1].split("/");
                final String m = CardFactoryUtil.extractOperators(calcX[1]);
                final Integer count = (Integer) root.getReplacingObject(AbilityKey.fromString(l[0]));

                val = doXMath(ObjectUtils.firstNonNull(count, 0), m, card, ability);
            } else { // these ones only for handling lists
                Iterable<Card> list = null;
                if (calcX[0].startsWith("Sacrificed")) {
                    list = sa.getRootAbility().getPaidList("Sacrificed");
                }
                else if (calcX[0].startsWith("Discarded")) {
                    final SpellAbility root = sa.getRootAbility();
                    list = root.getPaidList("Discarded");
                    if (null == list && root.isTrigger()) {
                        list = root.getHostCard().getSpellPermanent().getPaidList("Discarded");
                    }
                }
                else if (calcX[0].startsWith("Exiled")) {
                    list = sa.getRootAbility().getPaidList("Exiled");
                }
                else if (calcX[0].startsWith("Milled")) {
                    list = sa.getRootAbility().getPaidList("Milled");
                }
                else if (calcX[0].startsWith("Tapped")) {
                    list = sa.getRootAbility().getPaidList("Tapped");
                }
                else if (calcX[0].startsWith("Revealed")) {
                    list = sa.getRootAbility().getPaidList("Revealed");
                }
                else if (calcX[0].startsWith("Returned")) {
                    list = sa.getRootAbility().getPaidList("Returned");
                }
                else if (calcX[0].startsWith("Targeted")) {
                    list = sa.findTargetedCards();
                }
                else if (calcX[0].startsWith("ParentTargeted")) {
                    SpellAbility parent = sa.getParentTargetingCard();
                    if (parent != null) {
                        list = parent.findTargetedCards();
                    }
                }
                else if (calcX[0].startsWith("TriggerRemembered")) {
                    final SpellAbility root = sa.getRootAbility();
                    list = Iterables.filter(root.getTriggerRemembered(), Card.class);
                }
                else if (calcX[0].startsWith("TriggerObjects")) {
                    final SpellAbility root = sa.getRootAbility();
                    list = Iterables.filter((Iterable<?>) root.getTriggeringObject(AbilityKey.fromString(calcX[0].substring(14))), Card.class);
                }
                else if (calcX[0].startsWith("Triggered")) {
                    final SpellAbility root = sa.getRootAbility();
                    list = new CardCollection((Card) root.getTriggeringObject(AbilityKey.fromString(calcX[0].substring(9))));
                }
                else if (calcX[0].startsWith("Replaced")) {
                    final SpellAbility root = sa.getRootAbility();
                    list = new CardCollection((Card) root.getReplacingObject(AbilityKey.fromString(calcX[0].substring(8))));
                }
                if (list != null) {
                    // there could be null inside!
                    list = Iterables.filter(list, Card.class);
                    val = handlePaid(list, calcX[1], card, ability);
                }
            }
        }

        if (val != null) {
            if (maxto) {
                val = Math.max(val, 0);
            }
            return val * multiplier;
        }
        return 0;
    }

    /**
     * <p>
     * getDefinedObjects.
     * </p>
     *
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param def
     *            a {@link java.lang.String} object.
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a {@link java.util.ArrayList} object.
     */
    public static FCollection<GameObject> getDefinedObjects(final Card card, final String def, final CardTraitBase sa) {
        final FCollection<GameObject> objects = new FCollection<>();
        final String defined = (def == null) ? "Self" : def;

        objects.addAll(getDefinedPlayers(card, defined, sa));
        objects.addAll(getDefinedCards(card, defined, sa));
        objects.addAll(getDefinedSpellAbilities(card, defined, sa));
        return objects;
    }

    public static FCollection<GameEntity> getDefinedEntities(final Card card, final String def, final CardTraitBase sa) {
        final FCollection<GameEntity> objects = new FCollection<>();
        final String defined = (def == null) ? "Self" : def;

        objects.addAll(getDefinedPlayers(card, defined, sa));
        objects.addAll(getDefinedCards(card, defined, sa));
        return objects;
    }

    /**
     * Filter list by type.
     *
     * @param list
     *            a CardList
     * @param type
     *            a card type
     * @param sa
     *            a SpellAbility
     * @return a {@link forge.game.card.CardCollectionView} object.
     */
    public static CardCollectionView filterListByType(final CardCollectionView list, String type, final SpellAbility sa) {
        if (type == null) {
            return list;
        }

        // Filter List Can send a different Source card in for things like
        // Mishra and Lobotomy

        Card source = sa.getHostCard();
        final Object o;
        if (type.startsWith("Triggered")) {
            if (type.contains("Card")) {
                o = sa.getTriggeringObject(AbilityKey.Card);
            }
            else if (type.contains("Object")) {
                o = sa.getTriggeringObject(AbilityKey.Object);
            }
            else if (type.contains("Attacker")) {
                o = sa.getTriggeringObject(AbilityKey.Attacker);
            }
            else if (type.contains("Blocker")) {
                o = sa.getTriggeringObject(AbilityKey.Blocker);
            }
            else {
                o = sa.getTriggeringObject(AbilityKey.Card);
            }

            if (!(o instanceof Card)) {
                return new CardCollection();
            }

            if (type.equals("Triggered") || type.equals("TriggeredCard") || type.equals("TriggeredObject")
                || type.equals("TriggeredAttacker") || type.equals("TriggeredBlocker")) {
                type = "Card.Self";
            }

            source = (Card) (o);
            if (type.contains("TriggeredCard")) {
                type = TextUtil.fastReplace(type, "TriggeredCard", "Card");
            }
            else if (type.contains("TriggeredObject")) {
                type = TextUtil.fastReplace(type, "TriggeredObject", "Card");
            }
            else if (type.contains("TriggeredAttacker")) {
                type = TextUtil.fastReplace(type, "TriggeredAttacker", "Card");
            }
            else if (type.contains("TriggeredBlocker")) {
                type = TextUtil.fastReplace(type, "TriggeredBlocker", "Card");
            }
            else {
                type = TextUtil.fastReplace(type, "Triggered", "Card");
            }
        }
        else if (type.startsWith("Targeted")) {
            source = null;
            CardCollectionView tgts = sa.findTargetedCards();
            if (!tgts.isEmpty()) {
                source = tgts.get(0);
            }
            if (source == null) {
                return new CardCollection();
            }

            if (type.startsWith("TargetedCard")) {
                type = TextUtil.fastReplace(type, "TargetedCard", "Card");
            }
            else {
                type = TextUtil.fastReplace(type, "Targeted", "Card");
            }
        }
        else if (type.startsWith("Remembered")) {
            boolean hasRememberedCard = false;
            for (final Object object : source.getRemembered()) {
                if (object instanceof Card) {
                    hasRememberedCard = true;
                    source = (Card) object;
                    type = TextUtil.fastReplace(type, "Remembered", "Card");

                    break;
                }
            }

            if (!hasRememberedCard) {
                return new CardCollection();
            }
        }
        else if (type.startsWith("Imprinted")) {
            type = TextUtil.fastReplace(type, "Imprinted", "Card");
        }
        else if (type.equals("Card.AttachedBy")) {
            source = source.getEnchantingCard();
            type = TextUtil.fastReplace(type, "Card.AttachedBy", "Card.Self");
        }

        String valid = type;

        for (String t : cmpList) {
            int index = valid.indexOf(t);
            if (index >= 0) {
                char reference = valid.charAt(index + 2); // take whatever goes after EQ
                if (Character.isLetter(reference)) {
                    String varName = valid.split(",")[0].split(t)[1].split("\\+")[0];
                    if (!sa.getSVar(varName).isEmpty() || source.hasSVar(varName)) {
                        valid = TextUtil.fastReplace(valid, TextUtil.concatNoSpace(t, varName),
                                TextUtil.concatNoSpace(t, Integer.toString(calculateAmount(source, varName, sa))));
                    }
                }
            }
        }
        if (sa.hasParam("AbilityCount")) { // replace specific string other than "EQ" cases
            String var = sa.getParam("AbilityCount");
            valid = TextUtil.fastReplace(valid, var, Integer.toString(calculateAmount(source, var, sa)));
        }
        return CardLists.getValidCards(list, valid, sa.getActivatingPlayer(), source, sa);
    }

    /**
     * <p>
     * getDefinedPlayers.
     * </p>
     *
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param def
     *            a {@link java.lang.String} object.
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a {@link java.util.ArrayList} object.
     */
    public static PlayerCollection getDefinedPlayers(final Card card, final String def, final CardTraitBase sa) {
        final PlayerCollection players = new PlayerCollection();
        String changedDef = (def == null) ? "You" : applyAbilityTextChangeEffects(def, sa); // default to Self
        final String[] incR = changedDef.split("\\.", 2);
        String defined = incR[0];

        final Game game = card == null ? null : card.getGame();

        final Player player = sa instanceof SpellAbility ? ((SpellAbility)sa).getActivatingPlayer() : card.getController();

        if (defined.equals("Self") || defined.equals("TargetedCard") || defined.equals("ThisTargetedCard")
                || defined.startsWith("Valid") || getPaidCards(sa, defined) != null || defined.equals("TargetedSource")) {
            // defined syntax indicates cards only, so don't include any players
        } else if (defined.equals("TargetedOrController")) {
            players.addAll(getDefinedPlayers(card, "Targeted", sa));
            players.addAll(getDefinedPlayers(card, "TargetedController", sa));
        }
        else if ((defined.equals("Targeted") || defined.equals("TargetedPlayer")) && sa instanceof SpellAbility) {
            for (TargetChoices tc : ((SpellAbility)sa).getAllTargetChoices()) {
                players.addAll(tc.getTargetPlayers());
            }
        }
        else if (defined.equals("ParentTarget") && sa instanceof SpellAbility) {
            final SpellAbility parent = ((SpellAbility)sa).getParentTargetingPlayer();
            if (parent != null) {
                players.addAll(parent.getTargets().getTargetPlayers());
            }
        }
        else if (defined.equals("ThisTargetedPlayer") && sa instanceof SpellAbility) { // do not add parent targeted
            if (((SpellAbility)sa).getTargets() != null) {
                Iterables.addAll(players, ((SpellAbility)sa).getTargets().getTargetPlayers());
            }
        }
        else if (defined.equals("TargetedController")) {
            for (final Card c : getDefinedCards(card, "Targeted", sa)) {
                players.add(c.getController());
            }
            for (final SpellAbility s : getDefinedSpellAbilities(card, "Targeted", sa)) {
                players.add(s.getActivatingPlayer());
            }
        }
        else if (defined.equals("TargetedOwner")) {
            for (final Card c : getDefinedCards(card, "Targeted", sa)) {
                players.add(c.getOwner());
            }
        }
        else if (defined.equals("TargetedAndYou") && sa instanceof SpellAbility) {
            final SpellAbility saTargeting = ((SpellAbility)sa).getSATargetingPlayer();
            if (saTargeting != null) {
                players.addAll(saTargeting.getTargets().getTargetPlayers());
                players.add(((SpellAbility)sa).getActivatingPlayer());
            }
        }
        else if (defined.equals("ThisTargetedController")) {
            for (final Card c : getDefinedCards(card, "ThisTargetedCard", sa)) {
                players.add(c.getController());
            }
            for (final SpellAbility s : getDefinedSpellAbilities(card, "ThisTargeted", sa)) {
                players.add(s.getActivatingPlayer());
            }
        }
        else if (defined.equals("ParentTargetedController")) {
            for (final Card c : getDefinedCards(card, "ParentTarget", sa)) {
                players.add(c.getController());
            }
            for (final SpellAbility s : getDefinedSpellAbilities(card, "Targeted", sa)) {
                players.add(s.getActivatingPlayer());
            }
        }
        else if (defined.startsWith("Remembered")) {
            addPlayer(card.getRemembered(), defined, players);
        }
        else if (defined.startsWith("Imprinted")) {
            addPlayer(Lists.newArrayList(card.getImprintedCards()), defined, players);
        }
        else if (defined.startsWith("EffectSource")) {
            Card root = findEffectRoot(card);
            if (root != null) {
                addPlayer(Lists.newArrayList(root), defined, players);
            }
        } else if (defined.startsWith("OriginalHost")) {
            Card originalHost = sa.getOriginalHost();
            if (originalHost != null) {
                addPlayer(Lists.newArrayList(originalHost), defined, players);
            }
        }
        else if (defined.startsWith("DelayTriggerRemembered") && sa instanceof SpellAbility) {
            SpellAbility root = ((SpellAbility)sa).getRootAbility();
            if (root != null) {
                addPlayer(root.getTriggerRemembered(), defined, players);
            } else {
                System.err.println("Warning: couldn't find trigger SA in the chain of SpellAbility " + sa);
            }
        }
        else if (defined.startsWith("Triggered") && sa instanceof SpellAbility) {
            String defParsed = defined.endsWith("AndYou") ? defined.substring(0, defined.indexOf("AndYou")) : defined;
            if (defined.endsWith("AndYou")) {
                players.add(((SpellAbility)sa).getActivatingPlayer());
            }
            final SpellAbility root = ((SpellAbility)sa).getRootAbility();
            Object o = null;
            if (defParsed.endsWith("Controller")) {
                final boolean orCont = defParsed.endsWith("OrController") || defParsed.endsWith("OriginalController");
                String triggeringType = defParsed.substring(9);
                if (!triggeringType.equals("OriginalController")) { //certain triggering objects we don't want to trim
                    triggeringType = triggeringType.substring(0, triggeringType.length() - (orCont ? 12 : 10));
                }
                final Object c = root.getTriggeringObject(AbilityKey.fromString(triggeringType));
                if (orCont && c instanceof Player) {
                    o = c;
                } else if (c instanceof Card) {
                    o = ((Card) c).getController();
                } else if (c instanceof SpellAbility) {
                    o = ((SpellAbility) c).getActivatingPlayer();
                } else if (c instanceof Iterable<?>) { // For merged permanent
                    if (orCont) {
                        addPlayer(ImmutableList.copyOf(Iterables.filter((Iterable<Object>)c, Player.class)), "", players);
                    }
                    addPlayer(ImmutableList.copyOf(Iterables.filter((Iterable<Object>)c, Card.class)), "Controller", players);
                }
            }
            else if (defParsed.endsWith("Opponent")) {
                String triggeringType = defParsed.substring(9);
                triggeringType = triggeringType.substring(0, triggeringType.length() - 8);
                final Object c = root.getTriggeringObject(AbilityKey.fromString(triggeringType));
                if (c instanceof Card) {
                    o = ((Card) c).getController().getOpponents();
                }
                if (c instanceof SpellAbility) {
                    o = ((SpellAbility) c).getActivatingPlayer().getOpponents();
                }
                // For merged permanent
                if (c instanceof CardCollection) {
                    o = ((CardCollection) c).get(0).getController().getOpponents();
                }
            }
            else if (defParsed.endsWith("Owner")) {
                String triggeringType = defParsed.substring(9);
                triggeringType = triggeringType.substring(0, triggeringType.length() - 5);
                final Object c = root.getTriggeringObject(AbilityKey.fromString(triggeringType));
                if (c instanceof Card) {
                    o = ((Card) c).getOwner();
                }
                // For merged permanent
                if (c instanceof CardCollection) {
                    o = ((CardCollection) c).get(0).getOwner();
                }
            }
            else {
                String triggeringType = defParsed.substring(9);
                o = root.getTriggeringObject(AbilityKey.fromString(triggeringType));
            }
            if (o != null) {
                if (o instanceof Player) {
                    players.add((Player) o);
                }
                if (o instanceof Iterable) {
                    players.addAll(Iterables.filter((Iterable<?>)o, Player.class));
                }
            }
        }
        else if (defined.startsWith("OppNon")) {
            players.addAll(player.getOpponents());
            players.removeAll(getDefinedPlayers(card, defined.substring(6), sa));
        }
        else if (defined.startsWith("Replaced") && sa instanceof SpellAbility) {
            final SpellAbility root = ((SpellAbility)sa).getRootAbility();
            Object o = null;
            if (defined.endsWith("Controller")) {
                String replacingType = defined.substring(8);
                replacingType = replacingType.substring(0, replacingType.length() - 10);
                final Object c = root.getReplacingObject(AbilityKey.fromString(replacingType));
                if (c instanceof Card) {
                    o = ((Card) c).getController();
                }
                if (c instanceof SpellAbility) {
                    o = ((SpellAbility) c).getHostCard().getController();
                }
            }
            else if (defined.endsWith("Owner")) {
                String replacingType = defined.substring(8);
                replacingType = replacingType.substring(0, replacingType.length() - 5);
                final Object c = root.getReplacingObject(AbilityKey.fromString(replacingType));
                if (c instanceof Card) {
                    o = ((Card) c).getOwner();
                }
            }
            else {
                final String replacingType = defined.substring(8);
                o = root.getReplacingObject(AbilityKey.fromString(replacingType));
            }
            if (o != null) {
                if (o instanceof Player) {
                    players.add((Player) o);
                }
            }
        }
        else if (defined.startsWith("Non")) {
            players.addAll(game.getPlayersInTurnOrder());
            players.removeAll((FCollectionView<Player>)getDefinedPlayers(card, defined.substring(3), sa));
        }
        else if (defined.equals("EnchantedPlayer")) {
            final Object o = sa.getHostCard().getEntityAttachedTo();
            if (o instanceof Player) {
                players.add((Player) o);
            }
        }
        else if (defined.startsWith("Enchanted")) {
            if (card.isAttachedToEntity()) {
                addPlayer(Lists.newArrayList(card.getEntityAttachedTo()), defined, players);
            }
        }
        else if (defined.startsWith("Equipped")) {
            if (card.isEquipping()) {
                addPlayer(Lists.newArrayList(card.getEquipping()), defined, players);
            }
        }
        else if (defined.equals("AttackingPlayer")) {
            if (game.getPhaseHandler().inCombat()) {
                players.add(game.getCombat().getAttackingPlayer());
            }
        }
        else if (defined.equals("DefendingPlayer")) {
            players.add(game.getCombat().getDefendingPlayerRelatedTo(card));
        }
        else if (defined.equals("OpponentsOtherThanDefendingPlayer")) {
            players.addAll(player.getOpponents());
            players.remove(game.getCombat().getDefendingPlayerRelatedTo(card));
        }
        else if (defined.equals("ChosenPlayer")) {
            final Player p = card.getChosenPlayer();
            if (p != null) {
                players.add(p);
            }
        }
        else if (defined.equals("ChosenAndYou")) {
            players.add(player);
            final Player p = card.getChosenPlayer();
            if (p != null) {
                players.add(p);
            }
        }
        else if (defined.startsWith("ChosenCard")) {
            addPlayer(Lists.newArrayList(card.getChosenCards()), defined, players);
        }
        else if (defined.equals("SourceController")) {
            players.add(sa.getHostCard().getController());
        }
        else if (defined.equals("CardController")) {
            players.add(card.getController());
        }
        else if (defined.equals("CardOwner")) {
            players.add(card.getOwner());
        }
        else if (defined.startsWith("PlayerNamed_")) {
            for (Player p : game.getPlayersInTurnOrder()) {
                if (p.getName().equals(defined.substring(12))) {
                    players.add(p);
                }
            }
        }
        else if (defined.startsWith("Flipped")) {
            for (Player p : game.getPlayersInTurnOrder()) {
                if (null != sa.getHostCard().getFlipResult(p)) {
                    if (sa.getHostCard().getFlipResult(p).equals(defined.substring(7))) {
                        players.add(p);
                    }
                }
            }
        }
        else if (defined.equals("Caster")) {
            if (sa.getHostCard().wasCast()) {
                players.add((sa.getHostCard().getCastSA().getActivatingPlayer()));
            }
        }
        else if (defined.equals("ActivePlayer")) {
            players.add(game.getPhaseHandler().getPlayerTurn());
        }
        else if (defined.equals("You")) {
            players.add(player);
        }
        else if (defined.equals("Opponent")) {
            players.addAll(player.getOpponents());
        }
        else if (defined.startsWith("NextPlayerToYour")) {
            Direction dir = defined.substring(16).equals("Left") ? Direction.Left : Direction.Right;
            players.add(game.getNextPlayerAfter(player, dir));
        }
        else {
            // will be filtered below
            players.addAll(game.getPlayersInTurnOrder());
        }

        if (incR.length > 1 && !players.isEmpty()) {
            String[] valids = incR[1].split(",");
            // need to add valids onto all of them
            for (int i = 0; i < valids.length; i++) {
                valids[i] = "Player." + valids[i];
            }
            return players.filter(PlayerPredicates.restriction(valids, player, card, sa));
        }
        return players;
    }

    /**
     * <p>
     * getDefinedSpellAbilities.
     * </p>
     *
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param def
     *            a {@link java.lang.String} object.
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a {@link java.util.ArrayList} object.
     */
    public static FCollection<SpellAbility> getDefinedSpellAbilities(final Card card, final String def,
            final CardTraitBase sa) {
        final FCollection<SpellAbility> sas = new FCollection<>();
        final String defined = (def == null) ? "Self" : applyAbilityTextChangeEffects(def, sa); // default to Self
        final Game game = card.getGame();

        SpellAbility s = null;

        // TODO - this probably needs to be fleshed out a bit, but the basics work
        if (defined.equals("Self") && sa instanceof SpellAbility) {
            s = (SpellAbility)sa;
        }
        else if (defined.equals("Parent") && sa instanceof SpellAbility) {
            s = ((SpellAbility)sa).getRootAbility();
        }
        else if (defined.equals("Remembered")) {
            for (final Object o : card.getRemembered()) {
                if (o instanceof Card) {
                    final Card rem = (Card) o;
                    sas.addAll(game.getCardState(rem).getSpellAbilities());
                } else if (o instanceof SpellAbility) {
                    sas.add((SpellAbility) o);
                }
            }
        }
        else if (defined.equals("Imprinted")) {
            for (final Card imp : card.getImprintedCards()) {
                sas.addAll(imp.getSpellAbilities());
            }
        }
        else if (defined.equals("EffectSource")) {
            if (card.getEffectSourceAbility() != null) {
                sas.add(card.getEffectSourceAbility().getRootAbility());
            }
        }
        else if (defined.equals("SourceFirstSpell")) {
            SpellAbility spell = game.getStack().getSpellMatchingHost(card);
            if (spell != null) {
                sas.add(spell);
            }
        }
        else if (defined.startsWith("Triggered") && sa instanceof SpellAbility) {
            final SpellAbility root = ((SpellAbility)sa).getRootAbility();

            final String triggeringType = defined.substring(9);
            final Object o = root.getTriggeringObject(AbilityKey.fromString(triggeringType));
            if (o instanceof SpellAbility) {
                s = (SpellAbility) o;
            } else if (o instanceof SpellAbilityStackInstance) {
                s = ((SpellAbilityStackInstance) o).getSpellAbility(true);
            }
        }
        else if (defined.endsWith("Targeted") && sa instanceof SpellAbility) {
            final List<TargetChoices> targets = defined.startsWith("This") ? Arrays.asList(((SpellAbility)sa).getTargets()) : ((SpellAbility)sa).getAllTargetChoices();
            for (TargetChoices tc : targets) {
                for (SpellAbility targetSpell : tc.getTargetSpells()) {
                    SpellAbilityStackInstance stackInstance = game.getStack().getInstanceFromSpellAbility(targetSpell);
                    if (stackInstance != null) {
                        SpellAbility instanceSA = stackInstance.getSpellAbility(true);
                        if (instanceSA != null) {
                            sas.add(instanceSA);
                        }
                    } else {
                        sas.add(targetSpell);
                    }
                }
            }
        }

        if (s != null) {
            sas.add(s);
        }

        return sas;
    }


    /////////////////////////////////////////////////////////////////////////////////////
    //
    // BELOW ARE resove() METHOD AND ITS DEPENDANTS, CONSIDER MOVING TO DEDICATED CLASS
    //
    /////////////////////////////////////////////////////////////////////////////////////
    public static void resolve(final SpellAbility sa) {
        if (sa == null) {
            return;
        }

        Player pl = sa.getActivatingPlayer();
        final Game game = pl.getGame();

        if (sa.isTrigger() && sa.getParent() == null) {
            // when trigger cost are paid before the effect does resolve, need to clean the trigger
            game.getTriggerHandler().resetActiveTriggers();
        }

        // do blessing there before condition checks
        if (sa.isSpell() && sa.isBlessing() && !sa.getHostCard().isPermanent()) {
            if (pl.getZone(ZoneType.Battlefield).size() >= 10) {
                pl.setBlessing(true);
            }
        }

        // count times ability resolves this turn
        if (!sa.isWrapper()) {
            final Card host = sa.getHostCard();
            if (host != null) {
                host.addAbilityResolved(sa);
            }
        }

        final ApiType api = sa.getApi();
        if (api == null) {
            sa.resolve();
            if (sa.getSubAbility() != null) {
                resolve(sa.getSubAbility());
            }
            return;
        }
        AbilityUtils.resolveApiAbility(sa, game);
    }

    private static void resolveSubAbilities(final SpellAbility sa, final Game game) {
        final AbilitySub abSub = sa.getSubAbility();
        if (abSub == null || sa.isWrapper()) {
            return;
        }

        // Needed - Equip an untapped creature with Sword of the Paruns then cast Deadshot on it. Should deal 2 more damage.
        game.getAction().checkStaticAbilities(); // this will refresh continuous abilities for players and permanents.
        game.getTriggerHandler().resetActiveTriggers(!sa.isReplacementAbility());
        AbilityUtils.resolveApiAbility(abSub, game);
    }

    private static void resolveApiAbility(final SpellAbility sa, final Game game) {
        final Card card = sa.getHostCard();

        String msg = "AbilityUtils:resolveApiAbility: try to resolve API ability";
        Breadcrumb bread = new Breadcrumb(msg);
        bread.setData("Api", sa.getApi().toString());
        bread.setData("Card", card.getName());
        bread.setData("SA", sa.toString());
        Sentry.addBreadcrumb(bread);

        // check conditions
        if (sa.metConditions()) {
            if (sa.isWrapper() || StringUtils.isBlank(sa.getParam("UnlessCost"))) {
                sa.resolve();
            } else {
                handleUnlessCost(sa, game);
                return;
            }
        }
        resolveSubAbilities(sa, game);
    }

    private static void handleUnlessCost(final SpellAbility sa, final Game game) {
        final Card source = sa.getHostCard();

        // The player who has the chance to cancel the ability
        final String pays = sa.getParamOrDefault("UnlessPayer", "TargetedController");
        final FCollectionView<Player> allPayers = getDefinedPlayers(source, pays, sa);
        final String  resolveSubs = sa.getParam("UnlessResolveSubs"); // no value means 'Always'
        final boolean execSubsWhenPaid = "WhenPaid".equals(resolveSubs) || StringUtils.isBlank(resolveSubs);
        final boolean execSubsWhenNotPaid = "WhenNotPaid".equals(resolveSubs) || StringUtils.isBlank(resolveSubs);
        final boolean isSwitched = sa.hasParam("UnlessSwitched");

        // The cost
        Cost cost;
        String unlessCost = sa.getParam("UnlessCost").trim();
        if (unlessCost.equals("CardManaCost")) {
            cost = new Cost(source.getManaCost(), true);
        }
        else if (unlessCost.equals("TriggeredSpellManaCost")) {
            SpellAbility triggered = (SpellAbility) sa.getRootAbility().getTriggeringObject(AbilityKey.SpellAbility);
            Card triggeredCard = triggered.getHostCard();
            if (triggeredCard.getManaCost() == null) {
                cost = new Cost(ManaCost.ZERO, true);
            } else {
                int xCount = triggeredCard.getManaCost().countX();
                int xPaid = triggeredCard.getXManaCostPaid() * xCount;
                ManaCostBeingPaid toPay = new ManaCostBeingPaid(triggeredCard.getManaCost());
                toPay.decreaseShard(ManaCostShard.X, xCount);
                toPay.increaseGenericMana(xPaid);
                cost = new Cost(toPay.toManaCost(), true);
            }
        }
        else if (unlessCost.equals("ChosenManaCost")) {
            if (!source.hasChosenCard()) {
                cost = new Cost(ManaCost.ZERO, true);
            } else {
                cost = new Cost(Iterables.getFirst(source.getChosenCards(), null).getManaCost(), true);
            }
        }
        else if (unlessCost.equals("ChosenNumber")) {
            cost = new Cost(new ManaCost(new ManaCostParser(String.valueOf(source.getChosenNumber()))), true);
        }
        else if (unlessCost.startsWith("DefinedCost")) {
            CardCollection definedCards = getDefinedCards(source, unlessCost.split("_")[1], sa);
            if (definedCards.isEmpty()) {
                sa.resolve();
                resolveSubAbilities(sa, game);
                return;
            }
            Card card = definedCards.getFirst();
            ManaCostBeingPaid newCost = new ManaCostBeingPaid(card.getManaCost());
            // Check if there's a third underscore for cost modifying
            if (unlessCost.split("_").length == 3) {
                String modifier = unlessCost.split("_")[2];
                if (modifier.startsWith("Minus")) {
                    newCost.decreaseGenericMana(Integer.parseInt(modifier.substring(5)));
                } else {
                    newCost.increaseGenericMana(Integer.parseInt(modifier.substring(4)));
                }
            }
            cost = new Cost(newCost.toManaCost(), true);
        }
        else if (unlessCost.startsWith("DefinedSACost")) {
            FCollection<SpellAbility> definedSAs = getDefinedSpellAbilities(source, unlessCost.split("_")[1], sa);
            if (definedSAs.isEmpty()) {
                sa.resolve();
                resolveSubAbilities(sa, game);
                return;
            }
            Card host = definedSAs.getFirst().getHostCard();
            if (host.getManaCost() == null) {
                cost = new Cost(ManaCost.ZERO, true);
            } else {
                int xCount = host.getManaCost().countX();
                int xPaid = host.getXManaCostPaid() * xCount;
                ManaCostBeingPaid toPay = new ManaCostBeingPaid(host.getManaCost());
                toPay.decreaseShard(ManaCostShard.X, xCount);
                toPay.increaseGenericMana(xPaid);
                cost = new Cost(toPay.toManaCost(), true);
            }
        }
        else if (!StringUtils.isBlank(sa.getSVar(unlessCost)) || !StringUtils.isBlank(source.getSVar(unlessCost))) {
            // check for X costs (stored in SVars
            int xCost = calculateAmount(source, TextUtil.fastReplace(sa.getParam("UnlessCost"),
                    " ", ""), sa);
            //Check for XColor
            ManaCostBeingPaid toPay = new ManaCostBeingPaid(ManaCost.ZERO);
            byte xColor = ManaAtom.fromName(sa.getParamOrDefault("UnlessXColor", "1"));
            toPay.increaseShard(ManaCostShard.valueOf(xColor), xCost);
            cost = new Cost(toPay.toManaCost(), true);
        }
        else {
            cost = new Cost(unlessCost, true);
        }

        boolean alreadyPaid = false;
        for (Player payer : allPayers) {
            if (unlessCost.equals("LifeTotalHalfUp")) {
                String halfup = Integer.toString(Math.max(0,(int) Math.ceil(payer.getLife() / 2.0)));
                cost = new Cost("PayLife<" + halfup + ">", true);
            }
            alreadyPaid |= payer.getController().payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }

        if (alreadyPaid == isSwitched) {
            sa.resolve();
        }

        if (alreadyPaid && execSubsWhenPaid || !alreadyPaid && execSubsWhenNotPaid) { // switched refers only to main ability!
            resolveSubAbilities(sa, game);
        }
    }

    /**
     * <p>
     * handleRemembering.
     * </p>
     *
     * @param sa
     *            a SpellAbility object.
     */
    public static void handleRemembering(final SpellAbility sa) {
        Card host = sa.getHostCard();

        if (sa.hasParam("RememberTargets") && sa.usesTargeting()) {
            if (sa.hasParam("ForgetOtherTargets")) {
                host.clearRemembered();
            }
            host.addRemembered(sa.getTargets());
        }

        if (sa.hasParam("ImprintTargets") && sa.usesTargeting()) {
            host.addImprintedCards(sa.getTargets().getTargetCards());
        }

        if (sa.hasParam("RememberCostMana")) {
            host.clearRemembered();
            ManaCostBeingPaid activationMana = new ManaCostBeingPaid(sa.getPayCosts().getTotalMana());
            if (sa.getXManaCostPaid() != null) {
                activationMana.setXManaCostPaid(sa.getXManaCostPaid(), null);
            }
            int activationShards = activationMana.getConvertedManaCost();
            List<Mana> payingMana = sa.getPayingMana();
            // even if the cost was raised, we only care about mana from activation part
            // let's just assume the first shards spent are that for easy handling
            List<Mana> activationPaid = payingMana.subList(payingMana.size() - activationShards, payingMana.size());
            StringBuilder sb = new StringBuilder();
            int nMana = 0;
            for (Mana m : activationPaid) {
                if (nMana > 0) {
                    sb.append(" ");
                }
                sb.append(m.toString());
                nMana++;
            }
            host.addRemembered(sb.toString());
        }

        if (sa.hasParam("RememberCostCards") && !sa.getPaidHash().isEmpty()) {
            List <Card> noList = Lists.newArrayList();
            Map<String, CardCollection> paidLists = sa.getPaidHash();
            if (sa.hasParam("RememberCostExcept")) {
                noList.addAll(AbilityUtils.getDefinedCards(host, sa.getParam("RememberCostExcept"), sa));
            }
            if (paidLists.containsKey("Exiled")) {
                final CardCollection paidListExiled = sa.getPaidList("Exiled");
                for (final Card exiledAsCost : paidListExiled) {
                    if (!noList.contains(exiledAsCost)) {
                        host.addRemembered(exiledAsCost);
                    }
                }
            } else if (paidLists.containsKey("Sacrificed")) {
                final CardCollection paidListSacrificed = sa.getPaidList("Sacrificed");
                for (final Card sacrificedAsCost : paidListSacrificed) {
                    if (!noList.contains(sacrificedAsCost)) {
                        host.addRemembered(sacrificedAsCost);
                    }
                }
            } else if (paidLists.containsKey("Tapped")) {
                final CardCollection paidListTapped = sa.getPaidList("Tapped");
                for (final Card tappedAsCost : paidListTapped) {
                    if (!noList.contains(tappedAsCost)) {
                        host.addRemembered(tappedAsCost);
                    }
                }
            } else if (paidLists.containsKey("Unattached")) {
                final CardCollection paidListUnattached = sa.getPaidList("Unattached");
                for (final Card unattachedAsCost : paidListUnattached) {
                    if (!noList.contains(unattachedAsCost)) {
                        host.addRemembered(unattachedAsCost);
                    }
                }
            } else if (paidLists.containsKey("Discarded")) {
                final CardCollection paidListDiscarded = sa.getPaidList("Discarded");
                for (final Card discardedAsCost : paidListDiscarded) {
                    if (!noList.contains(discardedAsCost)) {
                        host.addRemembered(discardedAsCost);
                    }
                }
            }
        }
        // make sure that when this is from a trigger LKI is updated
        host.getGame().updateLastStateForCard(host);
    }

    /**
     * <p>
     * Parse non-mana X variables.
     * </p>
     *
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param s
     *            a {@link java.lang.String} object.
     * @param ctb
     *            a {@link forge.game.CardTraitBase} object.
     * @return a int.
     */
    public static int xCount(final Card c, final String s, final CardTraitBase ctb) {
        final String s2 = applyAbilityTextChangeEffects(s, ctb);
        final String[] l = s2.split("/");
        final String expr = CardFactoryUtil.extractOperators(s2);
        final Player player = ctb == null ? null : ctb instanceof SpellAbility ? ((SpellAbility)ctb).getActivatingPlayer() : ctb.getHostCard().getController();

        // accept straight numbers
        if (l[0].startsWith("Number$")) {
            final String number = l[0].substring(7);
            return doXMath(Integer.parseInt(number), expr, c, ctb);
        }

        if (l[0].startsWith("Count$")) {
            l[0] = l[0].substring(6);
        }

        if (l[0].startsWith("SVar$")) {
            String n = l[0].substring(5);
            String v = ctb == null ? c.getSVar(n) : ctb.getSVar(n);
            return doXMath(xCount(c, v, ctb), expr, c, ctb);
        }

        final String[] sq;
        sq = l[0].split("\\.");

        final Game game = c.getGame();

        if (ctb != null) {
            // Count$Compare <int comparator value>.<True>.<False>
            if (sq[0].startsWith("Compare")) {
                final String[] compString = sq[0].split(" ");
                final int lhs = calculateAmount(c, compString[1], ctb);
                final int rhs =  calculateAmount(c, compString[2].substring(2), ctb);
                boolean v = Expressions.compare(lhs, compString[2], rhs);
                return doXMath(calculateAmount(c, sq[v ? 1 : 2], ctb), expr, c, ctb);
            }
            if (ctb instanceof SpellAbility) {
                final SpellAbility sa = (SpellAbility) ctb;

                // special logic for xPaid in SpellAbility
                if (sq[0].contains("xPaid")) {
                    SpellAbility root = sa.getRootAbility();

                    // 107.3i If an object gains an ability, the value of X within that ability is the value defined by that ability,
                    // or 0 if that ability doesn’t define a value of X. This is an exception to rule 107.3h. This may occur with ability-adding effects, text-changing effects, or copy effects.
                    if (root.getXManaCostPaid() != null) {
                        return doXMath(root.getXManaCostPaid(), expr, c, ctb);
                    }

                    // If the chosen creature has X in its mana cost, that X is considered to be 0.
                    // The value of X in Altered Ego’s last ability will be whatever value was chosen for X while casting Altered Ego.
                    if (sa.isCopiedTrait() && !sa.getHostCard().equals(c)) {
                        return doXMath(0, expr, c, ctb);
                    }

                    if (root.isTrigger()) {
                        Trigger t = root.getTrigger();
                        if (t == null) {
                            return doXMath(0, expr, c, ctb);
                        }

                        // ImmediateTrigger should check for the Ability which created the trigger
                        if (t.getSpawningAbility() != null) {
                            root = t.getSpawningAbility().getRootAbility();
                            return doXMath(root.getXManaCostPaid() == null ? 0 : root.getXManaCostPaid(), expr, c, ctb);
                        }

                        // 107.3k If an object’s enters-the-battlefield triggered ability or replacement effect refers to X,
                        // and the spell that became that object as it resolved had a value of X chosen for any of its costs,
                        // the value of X for that ability is the same as the value of X for that spell, although the value of X for that permanent is 0.
                        if (TriggerType.ChangesZone.equals(t.getMode())
                                && ZoneType.Battlefield.name().equals(t.getParam("Destination"))) {
                           return doXMath(c.getXManaCostPaid(), expr, c, ctb);
                        } else if (TriggerType.SpellCast.equals(t.getMode())) {
                            // Cast Trigger like Hydroid Krasis, use SI because Unbound Flourishing might change X
                            SpellAbilityStackInstance castSI = (SpellAbilityStackInstance) root.getTriggeringObject(AbilityKey.StackInstance);
                            if (castSI == null) {
                                return doXMath(0, expr, c, ctb);
                            }
                            return doXMath(castSI.getXManaPaid(), expr, c, ctb);
                        } else if (TriggerType.Cycled.equals(t.getMode())) {
                            SpellAbility cycleSA = (SpellAbility) sa.getTriggeringObject(AbilityKey.Cause);
                            if (cycleSA == null || cycleSA.getXManaCostPaid() == null) {
                                return doXMath(0, expr, c, ctb);
                            }
                            return doXMath(cycleSA.getXManaCostPaid(), expr, c, ctb);
                        } else if (TriggerType.TurnFaceUp.equals(t.getMode())) {
                            SpellAbility turnupSA = (SpellAbility) sa.getTriggeringObject(AbilityKey.Cause);
                            if (turnupSA == null || turnupSA.getXManaCostPaid() == null) {
                                return doXMath(0, expr, c, ctb);
                            }
                            return doXMath(turnupSA.getXManaCostPaid(), expr, c, ctb);
                        }
                    }

                    if (root.isReplacementAbility() && sa.hasParam("ETB")) {
                        return doXMath(c.getXManaCostPaid(), expr, c, ctb);
                    }

                    return doXMath(0, expr, c, ctb);
                }

                // Count$Kicked.<numHB>.<numNotHB>
                if (sq[0].startsWith("Kicked")) {
                    boolean kicked = sa.isKicked() || c.getKickerMagnitude() > 0;
                    return doXMath(Integer.parseInt(kicked ? sq[1] : sq[2]), expr, c, ctb);
                }

                //Count$HasNumChosenColors.<DefinedCards related to spellability>
                if (sq[0].contains("HasNumChosenColors")) {
                    int sum = 0;
                    for (Card card : getDefinedCards(c, sq[1], sa)) {
                        sum += card.getColor().getSharedColors(ColorSet.fromNames(c.getChosenColors())).countColors();
                    }
                    return sum;
                }
                if (sq[0].startsWith("TriggerRememberAmount")) {
                    SpellAbility root = sa.getRootAbility();
                    int count = 0;
                    for (final Object o : root.getTriggerRemembered()) {
                        if (o instanceof Integer) {
                            count += (Integer) o;
                        }
                    }
                    return count;
                }
                // Count$TriggeredManaCostDevotion.<Color>
                if (sq[0].startsWith("TriggeredManaCostDevotion")) {
                    final SpellAbility root = sa.getRootAbility();
                    Card triggeringObject = (Card) root.getTriggeringObject(AbilityKey.Card);
                    int count = 0;
                    byte colorCode = ManaAtom.fromName(sq[1]);
                    for (ManaCostShard sh : triggeringObject.getManaCost()) {
                        if (sh.isColor(colorCode)) {
                            count++;
                        }
                    }
                    return count;
                }
                // Count$TriggeredPayingMana.<Color1>.<Color2>
                if (sq[0].startsWith("TriggeredPayingMana")) {
                    final SpellAbility root = sa.getRootAbility();
                    String mana = (String) root.getTriggeringObject(AbilityKey.PayingMana);
                    int count = 0;
                    Matcher mat = Pattern.compile(StringUtils.join(sq, "|", 1, sq.length)).matcher(mana);
                    while (mat.find()) {
                        count++;
                    }
                    return count;
                }
                // Count$TriggeredManaSpent
                if (sq[0].equals("TriggeredManaSpent")) {
                    final SpellAbility root = (SpellAbility) sa.getRootAbility().getTriggeringObject(AbilityKey.SpellAbility);
                    return root == null ? 0 : root.getTotalManaSpent();
                }

                // Count$ManaColorsPaid
                if (sq[0].equals("ManaColorsPaid")) {
                    final SpellAbility root = sa.getRootAbility();
                    return doXMath(root == null ? 0 : root.getPayingColors().countColors(), expr, c, ctb);
                }

                // Count$Adamant.<Color>.<True>.<False>
                if (sq[0].startsWith("Adamant")) {
                    final String payingMana = StringUtils.join(sa.getRootAbility().getPayingMana());
                    final int num = sq[0].length() > 7 ? Integer.parseInt(sq[0].split("_")[1]) : 3;
                    final boolean adamant = StringUtils.countMatches(payingMana, MagicColor.toShortString(sq[1])) >= num;
                    return doXMath(calculateAmount(c,sq[adamant ? 2 : 3], ctb), expr, c, ctb);
                }

                if (sq[0].startsWith("LastStateBattlefield")) {
                    final String[] k = l[0].split(" ");
                    CardCollectionView list;
                    // this is only for spells that were cast
                    if (sq[0].contains("WithFallback")) {
                        if (!sa.getHostCard().wasCast()) {
                            return doXMath(0, expr, c, ctb);
                        }
                        list = sa.getHostCard().getCastSA().getLastStateBattlefield();
                    } else {
                        list = sa.getLastStateBattlefield();
                    }
                    if (list == null || list.isEmpty()) {
                        // LastState is Empty
                        if (sq[0].contains("WithFallback")) {
                            list = game.getCardsIn(ZoneType.Battlefield);
                        } else {
                            return doXMath(0, expr, c, ctb);
                        }
                    }
                    list = CardLists.getValidCards(list, k[1], player, c, sa);
                    return doXMath(list.size(), expr, c, ctb);
                }

                if (sq[0].startsWith("LastStateGraveyard")) {
                    final String[] k = l[0].split(" ");
                    CardCollectionView list;
                    // this is only for spells that were cast
                    if (sq[0].contains("WithFallback")) {
                        if (!sa.getHostCard().wasCast()) {
                            return doXMath(0, expr, c, ctb);
                        }
                        list = sa.getHostCard().getCastSA().getLastStateGraveyard();
                    } else {
                        list = sa.getLastStateGraveyard();
                    }
                    if (sa.getLastStateGraveyard() == null || list.isEmpty()) {
                        // LastState is Empty
                        if (sq[0].contains("WithFallback")) {
                            list = game.getCardsIn(ZoneType.Graveyard);
                        } else {
                            return doXMath(0, expr, c, ctb);
                        }
                    }
                    list = CardLists.getValidCards(list, k[1], player, c, sa);
                    return doXMath(list.size(), expr, c, ctb);
                }

                if (sq[0].equals("ResolvedThisTurn")) {
                    return doXMath(sa.getResolvedThisTurn(), expr, c, ctb);
                }

                if (sq[0].startsWith("TotalManaSpent ")) {
                    final String[] k = sq[0].split(" ");
                    int v = 0;
                    if (sa.getRootAbility().getPayingMana() != null) {
                        for (Mana m : sa.getRootAbility().getPayingMana()) {
                            Card source = m.getSourceCard();
                            if (source != null) {
                                if (source.isValid(k[1].split(","), player, c, sa)) {
                                    v += 1;
                                }
                            }
                        }
                    }
                    return doXMath(v, expr, c, ctb);
                }

            } else {
                // fallback if ctb isn't a spellability
                if (sq[0].startsWith("LastStateBattlefield")) {
                    final String[] k = l[0].split(" ");
                    CardCollectionView list = game.getLastStateBattlefield();
                    list = CardLists.getValidCards(list, k[1], player, c, ctb);
                    return doXMath(list.size(), expr, c, ctb);
                }

                if (sq[0].startsWith("LastStateGraveyard")) {
                    final String[] k = l[0].split(" ");
                    CardCollectionView list = game.getLastStateGraveyard();
                    list = CardLists.getValidCards(list, k[1], player, c, ctb);
                    return doXMath(list.size(), expr, c, ctb);
                }

                if (sq[0].startsWith("xPaid")) {
                    return doXMath(c.getXManaCostPaid(), expr, c, ctb);
                }

            } // end SpellAbility

            if (sq[0].equals("CastTotalManaSpent")) {
                return doXMath(c.getCastSA() != null ? c.getCastSA().getTotalManaSpent() : 0, expr, c, ctb);
            }

            if (sq[0].startsWith("CastTotalManaSpent ")) {
                final String[] k = sq[0].split(" ");
                int v = 0;
                if (c.getCastSA() != null) {
                    for (Mana m : c.getCastSA().getPayingMana()) {
                        Card source = m.getSourceCard();
                        if (source != null) {
                            if (source.isValid(k[1].split(","), player, c, ctb)) {
                                v += 1;
                            }
                        }
                    }
                }
                return doXMath(v, expr, c, ctb);
            }

            // Count$TargetedLifeTotal (targeted player's life total)
            // Not optimal but since xCount doesn't take SAs, we need to replicate while we have it
            // Probably would be best if xCount took an optional SA to use in these circumstances
            if (sq[0].contains("TargetedLifeTotal")) {
                for (Player tgtP : getDefinedPlayers(c, "TargetedPlayer", ctb)) {
                    return doXMath(tgtP.getLife(), expr, c, ctb);
                }
            }

            // Count$DevotionDual.<color name>.<color name>
            // Count$Devotion.<color name>
            if (sq[0].contains("Devotion")) {
                int colorOccurrences = 0;
                String colorName = sq[1];
                if (colorName.contains("Chosen")) {
                    colorName = MagicColor.toShortString(c.getChosenColor());
                }
                byte colorCode = ManaAtom.fromName(colorName);
                if (sq[0].equals("DevotionDual")) {
                    colorCode |= ManaAtom.fromName(sq[2]);
                }
                for (Card c0 : player.getCardsIn(ZoneType.Battlefield)) {
                    for (ManaCostShard sh : c0.getManaCost()) {
                        if (sh.isColor(colorCode)) {
                            colorOccurrences++;
                        }
                    }
                    colorOccurrences += c0.getAmountOfKeyword("Your devotion to each color and each combination of colors is increased by one.");
                }
                return doXMath(colorOccurrences, expr, c, ctb);
            }

        } // end ctb != null

        if (sq[0].contains("OppsAtLifeTotal")) {
            final int lifeTotal = calculateAmount(c, sq[1], ctb);
            int number = 0;
            for (final Player opp : player.getOpponents()) {
                if (opp.getLife() == lifeTotal) {
                    number++;
                }
            }
            return doXMath(number, expr, c, ctb);
        }

        //Count$SearchedLibrary.<DefinedPlayer>
        if (sq[0].contains("SearchedLibrary")) {
            int sum = 0;
            for (Player p : getDefinedPlayers(c, sq[1], ctb)) {
                sum += p.getLibrarySearched();
            }
            return doXMath(sum, expr, c, ctb);
        }

        ////////////////////
        // card info

        // Count$CardMulticolor.<numMC>.<numNotMC>
        if (sq[0].contains("CardMulticolor")) {
            final boolean isMulti = c.getColor().isMulticolor();
            return doXMath(Integer.parseInt(sq[isMulti ? 1 : 2]), expr, c, ctb);
        }
        // Count$Madness.<True>.<False>
        if (sq[0].startsWith("Madness")) {
            return doXMath(calculateAmount(c, sq[c.isMadness() ? 1 : 2], ctb), expr, c, ctb);
        }

        // Count$Foretold.<True>.<False>
        if (sq[0].startsWith("Foretold")) {
            return doXMath(calculateAmount(c, sq[c.isForetold() ? 1 : 2], ctb), expr, c, ctb);
        }

        if (sq[0].startsWith("Kicked")) { // fallback for not spellAbility
            return doXMath(calculateAmount(c, sq[c.getKickerMagnitude() > 0 ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].startsWith("Escaped")) {
            return doXMath(calculateAmount(c, sq[c.getCastSA() != null && c.getCastSA().isEscape() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].startsWith("AltCost")) {
            return doXMath(calculateAmount(c, sq[c.isOptionalCostPaid(OptionalCost.AltCost) ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].startsWith("OptionalGenericCostPaid")) {
            return doXMath(calculateAmount(c, sq[c.isOptionalCostPaid(OptionalCost.Generic) ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("ColorsColorIdentity")) {
            return doXMath(c.getController().getCommanderColorID().countColors(), expr, c, ctb);
        }

        if (sq[0].equals("TotalDamageDoneByThisTurn")) {
            return doXMath(c.getTotalDamageDoneBy(), expr, c, ctb);
        }
        if (sq[0].equals("TotalDamageReceivedThisTurn")) {
            return doXMath(c.getAssignedDamage(), expr, c, ctb);
        }

        if (sq[0].contains("CardPower")) {
            return doXMath(c.getNetPower(), expr, c, ctb);
        }
        if (sq[0].contains("CardToughness")) {
            return doXMath(c.getNetToughness(), expr, c, ctb);
        }
        if (sq[0].contains("CardSumPT")) {
            return doXMath(c.getNetPower() + c.getNetToughness(), expr, c, ctb);
        }
        if (sq[0].contains("CardNumTypes")) {
            return doXMath(getNumberOfTypes(c), expr, c, ctb);
        }

        if (sq[0].contains("CardNumNotedTypes")) {
            return doXMath(c.getNumNotedTypes(), expr, c, ctb);
        }

        if (sq[0].contains("CardNumColors")) {
            return doXMath(c.getColor().countColors(), expr, c, ctb);
        }
        if (sq[0].contains("CardNumAttacksThisTurn")) {
            return doXMath(c.getDamageHistory().getCreatureAttacksThisTurn(), expr, c, ctb);
        }

        if (sq[0].contains("Intensity")) {
            return doXMath(c.getIntensity(true), expr, c, ctb);
        }

        if (sq[0].contains("CardCounters")) {
            // CardCounters.ALL to be used for Kinsbaile Borderguard and anything that cares about all counters
            int count = 0;
            if (sq[1].equals("ALL")) {
                for (Integer i : c.getCounters().values()) {
                    if (i != null && i > 0) {
                        count += i;
                    }
                }
            } else {
                count = c.getCounters(CounterType.getType(sq[1]));
            }
            return doXMath(count, expr, c, ctb);
        }

        if (sq[0].contains("BushidoPoint")) {
            return doXMath(c.getKeywordMagnitude(Keyword.BUSHIDO), expr, c, ctb);
        }
        if (sq[0].contains("TimesKicked")) {
            return doXMath(c.getKickerMagnitude(), expr, c, ctb);
        }
        if (sq[0].contains("TimesPseudokicked")) {
            return doXMath(c.getPseudoKickerMagnitude(), expr, c, ctb);
        }
        if (sq[0].contains("TimesMutated")) {
            return doXMath(c.getTimesMutated(), expr, c, ctb);
        }

        if (sq[0].equals("RegeneratedThisTurn")) {
            return doXMath(c.getRegeneratedThisTurn(), expr, c, ctb);
        }

        // Count$Converge
        if (sq[0].contains("Converge")) {
            SpellAbility castSA = c.getCastSA();
            return doXMath(castSA == null ? 0 : castSA.getPayingColors().countColors(), expr, c, ctb);
        }

        if (sq[0].startsWith("EachPhyrexianPaidWithLife")) {
            SpellAbility castSA = c.getCastSA();
            if (castSA == null) {
                return 0;
            }
            return doXMath(castSA.getSpendPhyrexianMana(), expr, c, ctb);
        }

        if (sq[0].startsWith("EachSpentToCast")) {
            SpellAbility castSA = c.getCastSA();
            if (castSA == null) {
                return 0;
            }
            final List<Mana> paidMana = castSA.getPayingMana();
            final String type = sq[1];
            int count = 0;
            for (Mana m : paidMana) {
                if (m.toString().equals(type)) {
                    count++;

                }
            }
            return doXMath(count, expr, c, ctb);
        }

        // Count$wasCastFrom<Zone>.<true>.<false>
        if (sq[0].startsWith("wasCastFrom")) {
            boolean your = sq[0].contains("Your");
            boolean byYou = sq[0].contains("ByYou");
            String strZone = sq[0].substring(11);
            if (your) {
                strZone = strZone.substring(4);
            }
            if (byYou) {
                strZone = strZone.substring(0, strZone.indexOf("ByYou", 0));
            }
            boolean zonesMatch = c.getCastFrom() != null && c.getCastFrom().getZoneType() == ZoneType.smartValueOf(strZone)
                    && (!byYou || player.equals(c.getCastSA().getActivatingPlayer()))
                    && (!your || c.getCastFrom().getPlayer().equals(player));
            return doXMath(calculateAmount(c, sq[zonesMatch ? 1 : 2], ctb), expr, c, ctb);
        }

        // Count$Presence_<Type>.<True>.<False>
        if (sq[0].startsWith("Presence")) {
            final String type = sq[0].split("_")[1];
            boolean found = false;
            if (c.getCastFrom() != null && c.getCastSA() != null) {
                int revealed = calculateAmount(c, "Revealed$Valid " + type, c.getCastSA());
                int ctrl = calculateAmount(c, "Count$LastStateBattlefield " + type + ".YouCtrl", c.getCastSA());
                if (revealed + ctrl >= 1) {
                    found = true;
                }
            }
            return doXMath(calculateAmount(c, sq[found ? 1 : 2], ctb), expr, c, ctb);
        }

        if (sq[0].startsWith("Devoured")) {
            final String validDevoured = sq[0].split(" ")[1];
            CardCollection cl = CardLists.getValidCards(c.getDevouredCards(), validDevoured, player, c, ctb);
            return doXMath(cl.size(), expr, c, ctb);
        }

        if (sq[0].contains("ChosenNumber")) {
            Integer i = c.getChosenNumber();
            return doXMath(i == null ? 0 : i, expr, c, ctb);
        }

        // Count$IfCastInOwnMainPhase.<numMain>.<numNotMain> // 7/10
        if (sq[0].contains("IfCastInOwnMainPhase")) {
            final PhaseHandler cPhase = game.getPhaseHandler();
            final boolean isMyMain = cPhase.getPhase().isMain() && cPhase.isPlayerTurn(player) && c.getCastFrom() != null;
            return doXMath(Integer.parseInt(sq[isMyMain ? 1 : 2]), expr, c, ctb);
        }

        // Count$AttachedTo <DefinedCards related to spellability> <restriction>
        if (sq[0].startsWith("AttachedTo")) {
            final String[] k = l[0].split(" ");
            int sum = 0;
            for (Card card : getDefinedCards(c, k[1], ctb)) {
                // Hateful Eidolon: the script uses LKI so that the attached cards have to be defined
                // This card needs the spellability ("Auras You control",  you refers to the activating player)
                // CardFactoryUtils.xCount doesn't have the sa parameter, SVar:X:TriggeredCard$Valid <restriction> cannot handle this
                sum += CardLists.getValidCardCount(card.getAttachedCards(), k[2], player, c, ctb);
            }
            return doXMath(sum, expr, c, ctb);
        }

        // Count$CardManaCost
        if (sq[0].contains("CardManaCost")) {
            Card ce;
            if (sq[0].contains("Remembered")) {
                ce = (Card) c.getFirstRemembered();
            }
            else {
                ce = c;
            }

            int cmc = ce == null ? 0 : ce.getCMC();

            if (sq[0].contains("LKI") && ctb instanceof SpellAbility && ce != null && !ce.isInZone(ZoneType.Stack) && ce.getManaCost() != null) {
                if (((SpellAbility) ctb).getXManaCostPaid() != null) {
                    cmc += ((SpellAbility) ctb).getXManaCostPaid() * ce.getManaCost().countX();
                }
            }

            return doXMath(cmc, expr, c, ctb);
        }

        if (sq[0].startsWith("RememberedSize")) {
            return doXMath(c.getRememberedCount(), expr, c, ctb);
        }

        if (sq[0].startsWith("ImprintedSize")) {
            return doXMath(c.getImprintedCards().size(), expr, c, ctb);
        }

        if (sq[0].startsWith("RememberedNumber")) {
            int num = 0;
            for (final Object o : c.getRemembered()) {
                if (o instanceof Integer) {
                    num += (Integer) o;
                }
            }
            return doXMath(num, expr, c, ctb);
        }

        if (sq[0].startsWith("RememberedWithSharedCardType")) {
            int maxNum = 1;
            for (final Object o : c.getRemembered()) {
                if (o instanceof Card) {
                    int num = 1;
                    Card firstCard = (Card) o;
                    for (final Object p : c.getRemembered()) {
                        if (p instanceof Card) {
                            Card secondCard = (Card) p;
                            if (!firstCard.equals(secondCard) && firstCard.sharesCardTypeWith(secondCard)) {
                                num++;
                            }
                        }
                    }
                    if (num > maxNum) {
                        maxNum = num;
                    }
                }
            }
            return doXMath(maxNum, expr, c, ctb);
        }

        // Count$EnchantedControllerCreatures
        if (sq[0].equals("EnchantedControllerCreatures")) { // maybe refactor into a Valid with ControlledBy
            int v = 0;
            if (c.getEnchantingCard() != null) {
                v = CardLists.count(c.getEnchantingCard().getController().getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.CREATURES);
            }
            return doXMath(v, expr, c, ctb);
        }

        ////////////////////////
        // player info
        if (sq[0].equals("Hellbent")) {
            return doXMath(calculateAmount(c, sq[player.hasHellbent() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("Metalcraft")) {
            return doXMath(calculateAmount(c, sq[player.hasMetalcraft() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("Delirium")) {
            return doXMath(calculateAmount(c, sq[player.hasDelirium() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("FatefulHour")) {
            return doXMath(calculateAmount(c, sq[player.getLife() <= 5 ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("Revolt")) {
            return doXMath(calculateAmount(c, sq[player.hasRevolt() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("Landfall")) {
            return doXMath(calculateAmount(c, sq[player.hasLandfall() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("Monarch")) {
            return doXMath(calculateAmount(c, sq[player.isMonarch() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("Initiative")) {
            return doXMath(calculateAmount(c, sq[player.hasInitiative() ? 1: 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("StartingPlayer")) {
            return doXMath(calculateAmount(c, sq[player.isStartingPlayer() ? 1: 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("Blessing")) {
            return doXMath(calculateAmount(c, sq[player.hasBlessing() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("Threshold")) {
            return doXMath(calculateAmount(c, sq[player.hasThreshold() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("ExtraTurn")) {
            return doXMath(calculateAmount(c, sq[game.getPhaseHandler().getPlayerTurn().isExtraTurn() ? 1 : 2], ctb), expr, c, ctb);
        }
        if (sq[0].equals("Averna")) {
            String str = "As you cascade, you may put a land card from among the exiled cards onto the " +
                    "battlefield tapped.";
            return doXMath(player.getKeywords().getAmount(str), expr, c, ctb);
        }
        if (sq[0].equals("YourStartingLife")) {
            return doXMath(player.getStartingLife(), expr, c, ctb);
        }

        if (sq[0].equals("YourLifeTotal")) {
            return doXMath(player.getLife(), expr, c, ctb);
        }
        if (sq[0].equals("OppGreatestLifeTotal")) {
            return doXMath(player.getOpponentsGreatestLifeTotal(), expr, c, ctb);
        }

        if (sq[0].equals("YouCycledThisTurn")) {
            return doXMath(player.getCycledThisTurn(), expr, c, ctb);
        }

        if (sq[0].equals("YouEquippedThisTurn")) {
            return doXMath(player.getEquippedThisTurn(), expr, c, ctb);
        }

        if (sq[0].equals("YouDrewThisTurn")) {
            return doXMath(player.getNumDrawnThisTurn(), expr, c, ctb);
        }

        if (sq[0].equals("YouRollThisTurn")) {
            return doXMath(player.getNumRollsThisTurn(), expr, c, ctb);
        }

        if (sq[0].equals("YouSurveilThisTurn")) {
            return doXMath(player.getSurveilThisTurn(), expr, c, ctb);
        }

        if (sq[0].equals("YouCastThisGame")) {
            return doXMath(player.getSpellsCastThisGame(), expr, c, ctb);
        }

        if (sq[0].equals("Night")) {
            return doXMath(calculateAmount(c, sq[game.isNight() ? 1 : 2], ctb), expr, c, ctb);
        }

        if (sq[0].contains("CardControllerTypes")) {
            return doXMath(getCardTypesFromList(player.getCardsIn(ZoneType.listValueOf(sq[1]))), expr, c, ctb);
        }

        if (sq[0].startsWith("CommanderCastFromCommandZone")) {
            // only used by Opal Palace, and it does add the trigger to the card
            return doXMath(player.getCommanderCast(c), expr, c, ctb);
        }

        if (l[0].startsWith("TotalCommanderCastFromCommandZone")) {
            return doXMath(player.getTotalCommanderCast(), expr, c, ctb);
        }

        if (sq[0].contains("LifeYouLostThisTurn")) {
            return doXMath(player.getLifeLostThisTurn(), expr, c, ctb);
        }
        if (sq[0].contains("LifeYouGainedThisTurn")) {
            return doXMath(player.getLifeGainedThisTurn(), expr, c, ctb);
        }
        if (sq[0].contains("LifeYourTeamGainedThisTurn")) {
            return doXMath(player.getLifeGainedByTeamThisTurn(), expr, c, ctb);
        }
        if (sq[0].contains("LifeYouGainedTimesThisTurn")) {
            return doXMath(player.getLifeGainedTimesThisTurn(), expr, c, ctb);
        }
        if (sq[0].contains("LifeOppsLostThisTurn")) {
            return doXMath(player.getOpponentLostLifeThisTurn(), expr, c, ctb);
        }
        if (sq[0].equals("BloodthirstAmount")) {
            return doXMath(player.getBloodthirstAmount(), expr, c, ctb);
        }

        if (sq[0].startsWith("YourCounters")) {
            // "YourCountersExperience" or "YourCountersPoison"
            String counterType = sq[0].substring(12);
            return doXMath(player.getCounters(CounterType.getType(counterType)), expr, c, ctb);
        }

        if (sq[0].contains("TotalOppPoisonCounters")) {
            return doXMath(player.getOpponentsTotalPoisonCounters(), expr, c, ctb);
        }

        if (sq[0].equals("MaxOppDamageThisTurn")) {
            return doXMath(player.getMaxOpponentAssignedDamage(), expr, c, ctb);
        }

        if (sq[0].contains("TotalDamageThisTurn")) {
            String[] props = l[0].split(" ");
            int sum = 0;
            for (Pair<Integer, Boolean> p : c.getDamageReceivedThisTurn()) {
                if (game.getDamageLKI(p).getLeft().isValid(props[1], player, c, ctb)) {
                    sum += p.getLeft();
                }
            }
            return doXMath(sum, expr, c, ctb);
        }

        if (sq[0].contains("DamageThisTurn")) {
            String[] props = l[0].split(" ");
            Boolean isCombat = null;
            if (sq[0].contains("CombatDamage")) {
                isCombat = true;
            }
            int num;
            List<Integer> dmgInstances = game.getDamageDoneThisTurn(isCombat, false, props[1], props[2], c, player, ctb);
            if (sq[0].contains("Max")) {
                num = Collections.max(dmgInstances);
            } else {
                num = dmgInstances.size();
            }
            return doXMath(num, expr, c, ctb);
        }

        if (sq[0].equals("YourTurns")) {
            return doXMath(player.getTurn(), expr, c, ctb);
        }

        if (sq[0].equals("NotedNumber")) {
            return doXMath(player.getNotedNumberForName(c.getName()), expr, c, ctb);
        }

        if (sq[0].startsWith("OppTypesInGrave")) {
            final PlayerCollection opponents = player.getOpponents();
            CardCollection oppCards = new CardCollection();
            oppCards.addAll(opponents.getCardsIn(ZoneType.Graveyard));
            return doXMath(getCardTypesFromList(oppCards), expr, c, ctb);
        }

        //Count$TypesSharedWith [defined]
        if (sq[0].startsWith("TypesSharedWith")) {
            Set<CardType.CoreType> thisTypes = Sets.newHashSet(c.getType().getCoreTypes());
            Set<CardType.CoreType> matches = new HashSet<>();
            for (Card c1 : AbilityUtils.getDefinedCards(ctb.getHostCard(), l[0].split(" ")[1], ctb)) {
                for (CardType.CoreType type : Sets.newHashSet(c1.getType().getCoreTypes())) {
                    if (thisTypes.contains(type)) {
                        matches.add(type);
                    }
                }
            }
            return matches.size();
        }

        // Count$TopOfLibraryCMC
        if (sq[0].equals("TopOfLibraryCMC")) {
            int cmc = player.getCardsIn(ZoneType.Library).isEmpty() ? 0 :
                player.getCardsIn(ZoneType.Library).getFirst().getCMC();
            return doXMath(cmc, expr, c, ctb);
        }

        // Count$AttackersDeclared
        if (sq[0].startsWith("AttackersDeclared")) {
            List<Card> attackers = player.getCreaturesAttackedThisTurn();
            List<Card> differentAttackers = new ArrayList<>();
            for (Card attacker : attackers) {
                boolean add = true;
                for (Card different : differentAttackers) {
                    if (different.equalsWithTimestamp(attacker)) {
                        add = false;
                        break;
                    }
                }
                if (add) {
                    differentAttackers.add(attacker);
                }
            }
            return doXMath(differentAttackers.size(), expr, c, ctb);
        }

        // Count$CardAttackedThisTurn <Valid>
        if (sq[0].startsWith("CreaturesAttackedThisTurn")) {
            final String[] workingCopy = l[0].split(" ", 2);
            final String validFilter = workingCopy[1];
            return doXMath(CardLists.getValidCardCount(player.getCreaturesAttackedThisTurn(), validFilter, player, c, ctb), expr, c, ctb);
        }

        // Manapool
        if (sq[0].startsWith("ManaPool")) {
            final String color = l[0].split(":")[1];
            int v = 0;
            if (color.equals("All")) {
                v = player.getManaPool().totalMana();
            } else {
                v = player.getManaPool().getAmountOfColor(ManaAtom.fromName(color));
            }
            return doXMath(v, expr, c, ctb);
        }

        // Count$Domain
        if (sq[0].startsWith("Domain")) {
            int n = 0;
            Player neededPlayer = sq[0].equals("DomainActivePlayer") ? game.getPhaseHandler().getPlayerTurn() : player;
            CardCollection someCards = neededPlayer.getLandsInPlay();
            for (String basic : MagicColor.Constant.BASIC_LANDS) {
                if (!CardLists.getType(someCards, basic).isEmpty()) {
                    n++;
                }
            }
            return doXMath(n, expr, c, ctb);
        }

        //SacrificedThisTurn <type>
        if (sq[0].startsWith("SacrificedThisTurn")) {
            List<Card> list = player.getSacrificedThisTurn();
            if (l[0].contains(" ")) {
                String[] lparts = l[0].split(" ", 2);
                String restrictions = TextUtil.fastReplace(l[0], TextUtil.addSuffix(lparts[0]," "), "");
                list = CardLists.getValidCardsAsList(list, restrictions, player, c, ctb);
            }
            return doXMath(list.size(), expr, c, ctb);
        }

        if (sq[0].contains("AbilityYouCtrl")) {
            CardCollection all = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), "Creature", player,
                    c, ctb);
            int count = 0;
            for (String ab : sq[0].substring(15).split(",")) {
                CardCollection found = CardLists.getValidCards(all, "Creature.with" + ab, player, c, ctb);
                if (!found.isEmpty()) {
                    count++;
                }
            }
            return doXMath(count, expr, c, ctb);
        }

        if (sq[0].contains("Party")) {
            CardCollection adventurers = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield),
                    "Creature.Cleric,Creature.Rogue,Creature.Warrior,Creature.Wizard", player, c, ctb);

            Set<String> partyTypes = Sets.newHashSet("Cleric", "Rogue", "Warrior", "Wizard");
            int partySize = 0;

            HashMap<String, Card> chosenParty = new HashMap<>();
            List<Card> wildcard = Lists.newArrayList();
            HashMap<Card, Set<String>> multityped = new HashMap<>();

            // Figure out how to count each class separately.
            for (Card card : adventurers) {
                // cards with all creature types will just return full list
                Set<String> creatureTypes = card.getType().getCreatureTypes();
                creatureTypes.retainAll(partyTypes);

                if (creatureTypes.size() == 4) {
                    wildcard.add(card);

                    if (wildcard.size() >= 4) {
                        break;
                    }
                    continue;
                } else if (creatureTypes.size() == 1) {
                    String type = (String)(creatureTypes.toArray()[0]);

                    if (!chosenParty.containsKey(type)) {
                        chosenParty.put(type, card);
                    }
                } else {
                    multityped.put(card, creatureTypes);
                }
            }

            partySize = Math.min(chosenParty.size() + wildcard.size(), 4);

            if (partySize < 4) {
                partyTypes.removeAll(chosenParty.keySet());

                // Here I'm left with just the party types that I haven't selected.
                for (Card multi : multityped.keySet()) {
                    Set<String> types = multityped.get(multi);
                    types.retainAll(partyTypes);

                    for (String type : types) {
                        chosenParty.put(type, multi);
                        partyTypes.remove(type);
                        break;
                    }
                }
            }

            partySize = Math.min(chosenParty.size() + wildcard.size(), 4);

            return doXMath(partySize, expr, c, ctb);
        }

        // TODO make AI part to understand Sunburst better so this isn't needed
        if (sq[0].startsWith("UniqueManaColorsProduced")) {
            boolean untappedOnly = sq[1].contains("ByUntappedSources");
            int uniqueColors = 0;
            CardCollectionView otb = player.getCardsIn(ZoneType.Battlefield);
            outer: for (byte color : MagicColor.WUBRG) {
                for (Card card : otb) {
                    if (!card.isTapped() || !untappedOnly) {
                        for (SpellAbility ma : card.getManaAbilities()) {
                            if (ma.canProduce(MagicColor.toShortString(color))) {
                                uniqueColors++;
                                continue outer;
                            }
                        }
                    }
                }
            }
            return doXMath(uniqueColors, expr, c, ctb);
        }

        // TODO change into checking SpellAbility
        if (sq[0].contains("xColorPaid")) {
            String[] attrs = sq[0].split(" ");
            StringBuilder colors = new StringBuilder();
            for (int i = 1; i < attrs.length; i++) {
                colors.append(attrs[i]);
            }
            return doXMath(c.getXManaCostPaidCount(colors.toString()), expr, c, ctb);
        }

        // Count$UrzaLands.<numHB>.<numNotHB>
        if (sq[0].startsWith("UrzaLands")) {
            return doXMath(calculateAmount(c, sq[player.hasUrzaLands() ? 1 : 2], ctb), expr, c, ctb);
        }

        /////////////////
        //game info
        // Count$Morbid.<True>.<False>
        if (sq[0].startsWith("Morbid")) {
            final List<Card> res = CardUtil.getThisTurnEntered(ZoneType.Graveyard, ZoneType.Battlefield, "Creature", c, ctb);
            return doXMath(calculateAmount(c, sq[res.size() > 0 ? 1 : 2], ctb), expr, c, ctb);
        }

        if (sq[0].startsWith("CreatureType")) {
            String[] sqparts = l[0].split(" ", 2);
            final String[] rest = sqparts[1].split(",");

            final CardCollectionView cardsInZones = sqparts[0].length() > 12
                ? game.getCardsIn(ZoneType.listValueOf(sqparts[0].substring(12)))
                : game.getCardsIn(ZoneType.Battlefield);

            CardCollection cards = CardLists.getValidCards(cardsInZones, rest, player, c, ctb);
            final Set<String> creatTypes = Sets.newHashSet();

            for (Card card : cards) {
                Iterables.addAll(creatTypes, card.getType().getCreatureTypes());
            }
            // filter out fun types?
            return doXMath(creatTypes.size(), expr, c, ctb);
        }

        // Count$Chroma.<color name>
        if (sq[0].startsWith("Chroma")) {
            final CardCollectionView cards;
            if (sq[0].contains("ChromaSource")) { // Runs Chroma for passed in Source card
                cards = new CardCollection(c);
            } else {
                ZoneType sourceZone = sq[0].contains("ChromaInGrave") ?  ZoneType.Graveyard : ZoneType.Battlefield;
                cards = player.getCardsIn(sourceZone);
            }

            int colorOcurrencices = 0;
            byte colorCode = ManaAtom.fromName(sq[1]);
            for (Card c0 : cards) {
                for (ManaCostShard sh : c0.getManaCost()) {
                    if (sh.isColor(colorCode))
                        colorOcurrencices++;
                }
            }
            return doXMath(colorOcurrencices, expr, c, ctb);
        }

        if (l[0].contains("ExactManaCost")) {
            String[] sqparts = l[0].split(" ", 2);
            final String[] rest = sqparts[1].split(",");

            final CardCollectionView cardsInZones = sqparts[0].length() > 13
                ? game.getCardsIn(ZoneType.listValueOf(sqparts[0].substring(13)))
                : game.getCardsIn(ZoneType.Battlefield);

            CardCollection cards = CardLists.getValidCards(cardsInZones, rest, player, c, ctb);
            final Set<String> manaCost = Sets.newHashSet();

            for (Card card : cards) {
                manaCost.add(card.getManaCost().getShortString());
            }

            return doXMath(manaCost.size(), expr, c, ctb);
        }

        if (sq[0].equals("StormCount")) {
            return doXMath(game.getStack().getSpellsCastThisTurn().size() - 1, expr, c, ctb);
        }

        if (sq[0].startsWith("RolledThisTurn")) {
            return game.getPhaseHandler().getPlanarDiceRolledthisTurn();
        }

        if (sq[0].contains("CardTypes")) {
            return doXMath(getCardTypesFromList(getDefinedCards(c, sq[1], ctb)), expr, c, ctb);
        }

        if (sq[0].equals("TotalTurns")) {
            return doXMath(game.getPhaseHandler().getTurn(), expr, c, ctb);
        }

        if (sq[0].equals("MaxDistinctOnStack")) {
            return doXMath(game.getStack().getMaxDistinctSources(), expr, c, ctb);
        }

        //Count$Random.<Min>.<Max>
        if (sq[0].equals("Random")) {
            int min = calculateAmount(c, sq[1], ctb);
            int max = calculateAmount(c, sq[2], ctb);

            return MyRandom.getRandom().nextInt(1+max-min) + min;
        }

        // Count$ThisTurnCast <Valid>
        // Count$LastTurnCast <Valid>
        if (sq[0].startsWith("ThisTurnCast") || sq[0].startsWith("LastTurnCast")) {
            final String[] workingCopy = l[0].split("_");
            final String validFilter = workingCopy[1];

            List<Card> res = Lists.newArrayList();
            if (workingCopy[0].contains("This")) {
                res = CardUtil.getThisTurnCast(validFilter, c, ctb);
            } else {
                res = CardUtil.getLastTurnCast(validFilter, c, ctb);
            }

            return doXMath(res.size(), expr, c, ctb);
        }

        // Count$ThisTurnEntered <ZoneDestination> [from <ZoneOrigin>] <Valid>
        if (sq[0].startsWith("ThisTurnEntered")) {
            final String[] workingCopy = l[0].split("_");

            ZoneType destination = ZoneType.smartValueOf(workingCopy[1]);
            final boolean hasFrom = workingCopy[2].equals("from");
            ZoneType origin = hasFrom ? ZoneType.smartValueOf(workingCopy[3]) : null;
            String validFilter = workingCopy[hasFrom ? 4 : 2] ;

            final List<Card> res = CardUtil.getThisTurnEntered(destination, origin, validFilter, c, ctb);
            return doXMath(res.size(), expr, c, ctb);
        }

        // Count$LastTurnEntered <ZoneDestination> [from <ZoneOrigin>] <Valid>
        if (sq[0].startsWith("LastTurnEntered")) {
            final String[] workingCopy = l[0].split("_");

            ZoneType destination = ZoneType.smartValueOf(workingCopy[1]);
            final boolean hasFrom = workingCopy[2].equals("from");
            ZoneType origin = hasFrom ? ZoneType.smartValueOf(workingCopy[3]) : null;
            String validFilter = workingCopy[hasFrom ? 4 : 2] ;

            final List<Card> res = CardUtil.getLastTurnEntered(destination, origin, validFilter, c, ctb);
            return doXMath(res.size(), expr, c, ctb);
        }

        if (sq[0].startsWith("CountersAddedThisTurn")) {
            final String[] parts = l[0].split(" ");
            CounterType cType = CounterType.getType(parts[1]);

            return doXMath(game.getCounterAddedThisTurn(cType, parts[2], parts[3], c, player, ctb), expr, c, ctb);
        }

        // count valid cards in any specified zone/s
        if (sq[0].startsWith("Valid")) {
            String[] paidparts = l[0].split("\\$", 2);
            String[] lparts = paidparts[0].split(" ", 2);

            CardCollectionView cardsInZones = null;
            if (lparts[0].contains("All")) {
                cardsInZones = game.getCardsInGame();
            } else {
                final List<ZoneType> zones = ZoneType.listValueOf(lparts[0].length() > 5 ? lparts[0].substring(5) : "Battlefield");
                boolean usedLastState = false;
                if (ctb instanceof SpellAbility && zones.size() == 1) {
                    SpellAbility sa = (SpellAbility) ctb;
                    if (sa.isReplacementAbility()) {
                        if (zones.get(0).equals(ZoneType.Battlefield)) {
                            cardsInZones = sa.getRootAbility().getLastStateBattlefield();
                            usedLastState = true;
                        } else if (zones.get(0).equals(ZoneType.Graveyard)) {
                            cardsInZones = sa.getRootAbility().getLastStateGraveyard();
                            usedLastState = true;
                        }
                    }
                }
                if (!usedLastState) {
                    cardsInZones = game.getCardsIn(zones);
                }
            }

            int cnt;
            if (paidparts.length > 1) {
                cnt = handlePaid(CardLists.getValidCards(cardsInZones, lparts[1], player, c, ctb), paidparts[1], c, ctb);
            } else {
                cnt = CardLists.getValidCardCount(cardsInZones, lparts[1], player, c, ctb);
            }
            return doXMath(cnt, expr, c, ctb);
        }

        if (sq[0].startsWith("MostCardName")) {
            String[] lparts = l[0].split(" ", 2);
            final String[] rest = lparts[1].split(",");

            final CardCollectionView cardsInZones = lparts[0].length() > 12
                ? game.getCardsIn(ZoneType.listValueOf(lparts[0].substring(12)))
                : game.getCardsIn(ZoneType.Battlefield);

            CardCollection cards = CardLists.getValidCards(cardsInZones, rest, player, c, ctb);
            final Map<String, Integer> map = Maps.newHashMap();
            for (final Card card : cards) {
                // Remove Duplicated types
                final String name = card.getName();
                Integer count = map.get(name);
                map.put(name, count == null ? 1 : count + 1);
            }
            int max = 0;
            for (final Entry<String, Integer> entry : map.entrySet()) {
                if (max < entry.getValue()) {
                    max = entry.getValue();
                }
            }
            return max;
        }

        if (sq[0].startsWith("DifferentCardNames_")) {
            final List<String> crdname = Lists.newArrayList();
            final String restriction = l[0].substring(19);
            CardCollection list = CardLists.getValidCards(game.getCardsInGame(), restriction, player, c, ctb);
            for (final Card card : list) {
                String name = card.getName();
                // CR 201.2b Those objects have different names only if each of them has at least one name and no two objects in that group have a name in common
                if (!crdname.contains(name) && !name.isEmpty()) {
                    crdname.add(name);
                }
            }
            return doXMath(crdname.size(), expr, c, ctb);
        }

        if (sq[0].startsWith("MostProminentCreatureType")) {
            String restriction = l[0].split(" ")[1];
            CardCollection list = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), restriction, player, c, ctb);
            return doXMath(CardFactoryUtil.getMostProminentCreatureTypeSize(list), expr, c, ctb);
        }

        if (sq[0].startsWith("SecondMostProminentColor")) {
            String restriction = l[0].split(" ")[1];
            CardCollection list = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), restriction, player, c, ctb);
            int[] colorSize = CardFactoryUtil.SortColorsFromList(list);
            return doXMath(colorSize[colorSize.length - 2], expr, c, ctb);
        }

        if (sq[0].startsWith("ColorsCtrl")) {
            final String restriction = l[0].substring(11);
            final CardCollection list = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), restriction, player, c, ctb);
            return doXMath(CardUtil.getColorsFromCards(list).countColors(), expr, c, ctb);
        }

        // TODO move below to handlePaid
        if (sq[0].startsWith("SumPower")) {
            final String[] restrictions = l[0].split("_");
            CardCollection filteredCards = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), restrictions[1], player, c, ctb);
            return doXMath(Aggregates.sum(filteredCards, CardPredicates.Accessors.fnGetNetPower), expr, c, ctb);
        }
        if (sq[0].startsWith("DifferentPower_")) {
            final String restriction = l[0].substring(15);
            CardCollection list = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), restriction, player, c, ctb);
            final Iterable<Card> powers = Aggregates.uniqueByLast(list, CardPredicates.Accessors.fnGetNetPower);
            return doXMath(Iterables.size(powers), expr, c, ctb);
        }
        if (sq[0].startsWith("DifferentCounterKinds_")) {
            final List<CounterType> kinds = Lists.newArrayList();
            final String rest = l[0].substring(22);
            CardCollection list = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), rest, player, c, ctb);
            for (final Card card : list) {
                for (final Map.Entry<CounterType, Integer> map : card.getCounters().entrySet()) {
                    if (!kinds.contains(map.getKey())) {
                        kinds.add(map.getKey());
                    }
                }
            }
            return doXMath(kinds.size(), expr, c, ctb);
        }

        // Complex counting methods
        CardCollectionView someCards = getCardListForXCount(c, player, sq, ctb);

        return doXMath(someCards.size(), expr, c, ctb);
    }

    public static final void applyManaColorConversion(ManaConversionMatrix matrix, final Map<String, String> params) {
        String conversion = params.get("ManaConversion");

        for (String pair : conversion.split(" ")) {
            // Check if conversion is additive or restrictive and how to split
            boolean additive = pair.contains("->");
            String[] sides = pair.split(additive ? "->" : "<-");

            if (sides[0].equals("AnyColor") || sides[0].equals("AnyType")) {
                for (byte c : (sides[0].equals("AnyColor") ? MagicColor.WUBRG : MagicColor.WUBRGC)) {
                    matrix.adjustColorReplacement(c, ManaAtom.fromConversion(sides[1]), additive);
                }
            } else {
                matrix.adjustColorReplacement(ManaAtom.fromConversion(sides[0]), ManaAtom.fromConversion(sides[1]), additive);
            }
        }
    }

    public static final List<SpellAbility> getBasicSpellsFromPlayEffect(final Card tgtCard, final Player controller) {
        List<SpellAbility> sas = new ArrayList<>();
        List<SpellAbility> list = Lists.newArrayList(tgtCard.getBasicSpells());

        CardState original = tgtCard.getState(CardStateName.Original);
        if (tgtCard.isFaceDown()) {
            list.addAll(Lists.newArrayList(tgtCard.getBasicSpells(original)));
        } else {
            if (tgtCard.isLand()) {
                LandAbility la = new LandAbility(tgtCard, controller, null);
                la.setCardState(original);
                list.add(la);
            }
            if (tgtCard.isModal()) {
                CardState modal = tgtCard.getState(CardStateName.Modal);
                list.addAll(Lists.newArrayList(tgtCard.getBasicSpells(modal)));
                if (modal.getType().isLand()) {
                    LandAbility la = new LandAbility(tgtCard, controller, null);
                    la.setCardState(modal);
                    list.add(la);
                }
            }
        }

        for (SpellAbility s : list) {
            if (s instanceof LandAbility) {
                // CR 305.3
                if (controller.getGame().getPhaseHandler().isPlayerTurn(controller) && controller.canPlayLand(tgtCard, true, s)) {
                    sas.add(s);
                }
            } else {
                final Spell newSA = (Spell) s.copy(controller);
                SpellAbilityRestriction res = new SpellAbilityRestriction();
                // timing restrictions still apply
                res.setPlayerTurn(s.getRestrictions().getPlayerTurn());
                res.setOpponentTurn(s.getRestrictions().getOpponentTurn());
                res.setPhases(s.getRestrictions().getPhases());
                res.setZone(null);
                newSA.setRestrictions(res);
                // timing restrictions still apply
                if (res.checkTimingRestrictions(tgtCard, newSA)
                        // still need to check the other restrictions like Aftermath
                        && res.checkOtherRestrictions(tgtCard, newSA, controller)) {
                    sas.add(newSA);
                }
            }
        }
        return sas;
    }

    public static final String applyAbilityTextChangeEffects(final String def, final CardTraitBase ability) {
        if (ability == null || !ability.isIntrinsic() || ability.hasParam("LockInText")) {
            return def;
        }
        return applyTextChangeEffects(def, ability.getHostCard(), false);
    }

    public static final String applyKeywordTextChangeEffects(final String kw, final Card card) {
        if (!CardUtil.isKeywordModifiable(kw)) {
            return kw;
        }
        return applyTextChangeEffects(kw, card, false);
    }

    public static final String applyDescriptionTextChangeEffects(final String def, final CardTraitBase ability) {
        if (ability == null || !ability.isIntrinsic() || ability.hasParam("LockInText")) {
            return def;
        }
        return applyTextChangeEffects(def, ability.getHostCard(), true);
    }

    /**
     * Apply description-based text changes of a {@link Card} to a String. No
     * checks are made on traits being intrinsic.
     *
     * @param def a String.
     * @param card a {@link Card}.
     * @return a new String, taking text changes into account.
     */
    public static final String applyDescriptionTextChangeEffects(final String def, final Card card) {
        return applyTextChangeEffects(def, card, true);
    }

    private static final String applyTextChangeEffects(final String def, final Card card, final boolean isDescriptive) {
        return applyTextChangeEffects(def, isDescriptive,
                card.getChangedTextColorWords(), card.getChangedTextTypeWords());
    }

    public static final String applyTextChangeEffects(final String def, final boolean isDescriptive,
            Map<String,String> colorMap, Map<String,String> typeMap) {
        if (StringUtils.isEmpty(def)) {
            return def;
        }

        String replaced = def;
        for (final Entry<String, String> e : colorMap.entrySet()) {
            final String key = e.getKey();
            String value;
            if (key.equals("Any")) {
                for (final byte c : MagicColor.WUBRG) {
                    final String colorLowerCase = MagicColor.toLongString(c).toLowerCase(),
                            colorCaptCase = StringUtils.capitalize(MagicColor.toLongString(c));
                    // Color should not replace itself.
                    if (e.getValue().equalsIgnoreCase(colorLowerCase)) {
                        continue;
                    }
                    value = getReplacedText(colorLowerCase, e.getValue(), isDescriptive);
                    replaced = replaced.replaceAll("(?<!>)" + colorLowerCase, value.toLowerCase());
                    value = getReplacedText(colorCaptCase, e.getValue(), isDescriptive);
                    replaced = replaced.replaceAll("(?<!>)" + colorCaptCase, StringUtils.capitalize(value));
                }
            } else {
                value = getReplacedText(key, e.getValue(), isDescriptive);
                replaced = replaced.replaceAll("(?<!>)" + key, value);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String replacedPart : replaced.split(String.format(WITH_DELIMITER, "\\."))) {
            if (!replacedPart.equals("Self") && !replacedPart.equals(".")) {
                for (final Entry<String, String> e : typeMap.entrySet()) {
                    final String key = e.getKey();
                    final String pkey = CardType.getPluralType(key);
                    final String pvalue = getReplacedText(pkey, CardType.getPluralType(e.getValue()), isDescriptive);
                    replacedPart = replacedPart.replaceAll("(?<!>)" + pkey, pvalue);
                    final String value = getReplacedText(key, e.getValue(), isDescriptive);
                    replacedPart = replacedPart.replaceAll("(?<!>)" + key, value);
                }
            }
            sb.append(replacedPart);
        }
        return sb.toString();
    }

    private static final String getReplacedText(final String originalWord, final String newWord, final boolean isDescriptive) {
        if (isDescriptive) {
            return "<strike>" + originalWord + "</strike> " + newWord;
        }
        return newWord;
    }

    public static final String getSVar(final CardTraitBase ability, final String sVarName) {
        String val = ability.getSVar(sVarName);
        if (!ability.isIntrinsic() || StringUtils.isEmpty(val)) {
            return val;
        }
        return applyAbilityTextChangeEffects(val, ability);
    }

    private static void addPlayer(Iterable<Object> objects, final String def, FCollection<Player> players) {
        addPlayer(objects, def, players, false);
    }

    private static void addPlayer(Iterable<Object> objects, final String def, FCollection<Player> players, boolean skipRemembered) {
        for (Object o : objects) {
            if (o instanceof Player) {
                final Player p = (Player) o;
                if (def.endsWith("Opponents")) {
                    players.addAll(p.getOpponents());
                } else {
                    players.add(p);
                }
            } else if (o instanceof Card) {
                final Card c = (Card) o;
                if (def.endsWith("Controller")) {
                    players.add(c.getController());
                } else if (def.endsWith("Owner")) {
                    players.add(c.getOwner());
                } else if (def.endsWith("Remembered") && !skipRemembered) {
                    //fixme recursive call to skip so it will not cause StackOverflow, ie Riveteers Overlook
                    addPlayer(c.getRemembered(), def, players, true);
                }
            } else if (o instanceof SpellAbility) {
                final SpellAbility c = (SpellAbility) o;
                if (def.endsWith("Controller")) {
                    players.add(c.getHostCard().getController());
                }
            }
        }
    }

    public static SpellAbility addSpliceEffects(final SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Player player = sa.getActivatingPlayer();

        if (!sa.isSpell() || source.isCopiedSpell()) {
            return sa;
        }

        final CardCollectionView hand = player.getCardsIn(ZoneType.Hand);

        if (hand.isEmpty()) {
            return sa;
        }

        final CardCollection splices = CardLists.filter(hand, new Predicate<Card>() {
            @Override
            public boolean apply(Card input) {
                for (final KeywordInterface inst : input.getKeywords(Keyword.SPLICE)) {
                    String k = inst.getOriginal();
                    final String[] n = k.split(":");
                    if (source.isValid(n[1].split(","), player, input, sa)) {
                        return true;
                    }
                }
                return false;
            }
        });

        splices.remove(source);

        if (splices.isEmpty()) {
            return sa;
        }

        final List<Card> choosen = player.getController().chooseCardsForSplice(sa, splices);

        if (choosen.isEmpty()) {
            return sa;
        }

        final SpellAbility newSA = sa.copy();
        for (final Card c : choosen) {
            addSpliceEffect(newSA, c);
        }
        return newSA;
    }

    public static void addSpliceEffect(final SpellAbility sa, final Card c) {
        Cost spliceCost = null;
        // This Function thinks that Splice exist only once on the card
        for (final KeywordInterface inst : c.getKeywords(Keyword.SPLICE)) {
            final String k = inst.getOriginal();
            final String[] n = k.split(":");
            spliceCost = new Cost(n[2], false);
        }

        if (spliceCost == null)
            return;

        SpellAbility firstSpell = c.getFirstSpellAbility();
        Map<String, String> params = Maps.newHashMap(firstSpell.getMapParams());
        ApiType api = AbilityRecordType.getRecordType(params).getApiTypeOf(params);
        AbilitySub subAbility = (AbilitySub) AbilityFactory.getAbility(AbilityRecordType.SubAbility, api, params, null, c.getCurrentState(), c.getCurrentState());

        subAbility.setActivatingPlayer(sa.getActivatingPlayer());
        subAbility.setHostCard(sa.getHostCard());

        //add the spliced ability to the end of the chain
        sa.appendSubAbility(subAbility);

        // update master SpellAbility
        sa.setBasicSpell(false);
        sa.setPayCosts(spliceCost.add(sa.getPayCosts()));
        sa.setDescription(sa.getDescription() + " (Splicing " + c + " onto it)");
        sa.addSplicedCards(c);
    }

    public static int doXMath(final int num, final String operators, final Card c, CardTraitBase ctb) {
        if (operators == null || operators.equals("none")) {
            return num;
        }

        final String[] s = operators.split("\\.");
        int secondaryNum = 0;

        try {
            if (s.length == 2) {
                secondaryNum = Integer.parseInt(s[1]);
            }
        } catch (final Exception e) {
            secondaryNum = calculateAmount(c, s[1], ctb);
        }

        if (s[0].contains("Plus")) {
            return num + secondaryNum;
        } else if (s[0].contains("NMinus")) {
            return secondaryNum - num;
        } else if (s[0].contains("Minus")) {
            return num - secondaryNum;
        } else if (s[0].contains("Twice")) {
            return num * 2;
        } else if (s[0].contains("Thrice")) {
            return num * 3;
        } else if (s[0].contains("HalfUp")) {
            return (int) (Math.ceil(num / 2.0));
        } else if (s[0].contains("HalfDown")) {
            return (int) (Math.floor(num / 2.0));
        } else if (s[0].contains("ThirdUp")) {
            return (int) (Math.ceil(num / 3.0));
        } else if (s[0].contains("ThirdDown")) {
            return (int) (Math.floor(num / 3.0));
        } else if (s[0].contains("Negative")) {
            return num * -1;
        } else if (s[0].contains("Times")) {
            return num * secondaryNum;
        } else if (s[0].contains("DivideEvenlyDown")) {
            if (secondaryNum == 0) {
                return 0;
            }
            return num / secondaryNum;
        } else if (s[0].contains("Mod")) {
            return num % secondaryNum;
        } else if (s[0].contains("Abs")) {
            return Math.abs(num);
        } else if (s[0].contains("LimitMax")) {
            if (num < secondaryNum) {
                return num;
            }
            return secondaryNum;
        } else if (s[0].contains("LimitMin")) {
            if (num > secondaryNum) {
                return num;
            }
            return secondaryNum;

        } else {
            return num;
        }
    }

    /**
     * <p>
     * Parse player targeted X variables.
     * </p>
     *
     * @param players
     *            a {@link java.util.ArrayList} object.
     * @param s
     *            a {@link java.lang.String} object.
     * @param source
     *            a {@link forge.game.card.Card} object.
     * @return a int.
     */
    public static int playerXCount(final List<Player> players, final String s, final Card source, CardTraitBase ctb) {
        if (players.size() == 0) {
            return 0;
        }

        final String[] l = s.split("/");
        final String m = CardFactoryUtil.extractOperators(s);

        int n = 0;

        if (l[0].startsWith("TotalCommanderCastFromCommandZone")) {
            int totCast = 0;
            for (Player p : players) {
                totCast += p.getTotalCommanderCast();
            }
            return doXMath(totCast, m, source, ctb);
        }

        // methods for getting the highest/lowest playerXCount from a range of players
        if (l[0].startsWith("Highest")) {
            for (final Player player : players) {
                final int current = playerXProperty(player, TextUtil.fastReplace(s, "Highest", ""), source, ctb);
                if (current > n) {
                    n = current;
                }
            }

            return doXMath(n, m, source, ctb);
        }

        if (l[0].startsWith("Lowest")) {
            n = 99999; // if no players have fewer than 99999 valids, the game is frozen anyway
            for (final Player player : players) {
                final int current = playerXProperty(player, TextUtil.fastReplace(s, "Lowest", ""), source, ctb);
                if (current < n) {
                    n = current;
                }
            }
            return doXMath(n, m, source, ctb);
        }

        if (l[0].startsWith("TiedForHighestLife")) {
            int maxLife = Integer.MIN_VALUE;
            for (final Player player : players) {
                int highestTotal = playerXProperty(player, "LifeTotal", source, ctb);
                if (highestTotal > maxLife) {
                    maxLife = highestTotal;
                }
            }
            int numTied = 0;
            for (final Player player : players) {
                if (player.getLife() == maxLife) {
                    numTied++;
                }
            }
            return doXMath(numTied, m, source, ctb);
        }

        if (l[0].startsWith("TiedForLowestLife")) {
            int minLife = Integer.MAX_VALUE;
            for (final Player player : players) {
                int lowestTotal = playerXProperty(player, "LifeTotal", source, ctb);
                if (lowestTotal < minLife) {
                    minLife = lowestTotal;
                }
            }
            int numTied = 0;
            for (final Player player : players) {
                if (player.getLife() == minLife) {
                    numTied++;
                }
            }
            return doXMath(numTied, m, source, ctb);
        }

        final String[] sq;
        sq = l[0].split("\\.");

        // the number of players passed in
        if (sq[0].equals("Amount")) {
            return doXMath(players.size(), m, source, ctb);
        }

        if (sq[0].startsWith("HasProperty")) {
            int totPlayer = 0;
            String property = sq[0].substring(11);
            for (Player p : players) {
                if (p.hasProperty(property, source.getController(), source, ctb)) {
                    totPlayer++;
                }
            }
            return doXMath(totPlayer, m, source, ctb);
        }

        if (l[0].startsWith("Condition")) {
            int totPlayer = 0;
            String[] parts = l[0].split(" ", 2);
            boolean def = parts[0].equals("Condition");
            String comparator = !def ? parts[0].substring(9, 11) : "GE";
            int y = !def ? calculateAmount(source, parts[0].substring(11), ctb) : 1;
            for (Player p : players) {
                int x = playerXProperty(p, parts[1], source, ctb);
                if (Expressions.compare(x, comparator, y)) {
                    totPlayer++;
                }
            }
            return doXMath(totPlayer, m, source, ctb);
        }

        if (sq[0].contains("DamageThisTurn")) {
            int totDmg = 0;
            for (Player p : players) {
                totDmg += p.getAssignedDamage();
            }
            return doXMath(totDmg, m, source, ctb);
        }

        if (players.size() > 0) {
            int totCount = 0;
            for (Player p : players) {
                totCount += playerXProperty(p, s, source, ctb);
            }
            return totCount;
        }

        return doXMath(n, m, source, ctb);
    }

    public static int playerXProperty(final Player player, final String s, final Card source, CardTraitBase ctb) {
        final String[] l = s.split("/");
        final String m = CardFactoryUtil.extractOperators(s);

        final Game game = player.getGame();

        // count valid cards in any specified zone/s
        if (l[0].startsWith("Valid") && !l[0].contains("Valid ")) {
            String[] lparts = l[0].split(" ", 2);
            final List<ZoneType> vZone = ZoneType.listValueOf(lparts[0].split("Valid")[1]);
            String restrictions = TextUtil.fastReplace(l[0], TextUtil.addSuffix(lparts[0]," "), "");
            CardCollection cards = CardLists.getValidCards(game.getCardsIn(vZone), restrictions, player, source, ctb);
            return doXMath(cards.size(), m, source, ctb);
        }

        // count valid cards on the battlefield
        if (l[0].startsWith("Valid ")) {
            final String restrictions = l[0].substring(6);
            CardCollection cardsonbattlefield = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), restrictions, player, source, ctb);
            return doXMath(cardsonbattlefield.size(), m, source, ctb);
        }

        if (l[0].startsWith("ThisTurnEntered")) {
            final String[] workingCopy = l[0].split("_");

            ZoneType destination = ZoneType.smartValueOf(workingCopy[1]);
            final boolean hasFrom = workingCopy[2].equals("from");
            ZoneType origin = hasFrom ? ZoneType.smartValueOf(workingCopy[3]) : null;
            String validFilter = workingCopy[hasFrom ? 4 : 2] ;

            final List<Card> res = CardUtil.getThisTurnEntered(destination, origin, validFilter, source, ctb, player);
            return doXMath(res.size(), m, source, ctb);
        }

        final String[] sq = l[0].split("\\.");
        final String value = sq[0];

        if (value.contains("NumPowerSurgeLands")) {
            return doXMath(player.getNumPowerSurgeLands(), m, source, ctb);
        }

        if (value.contains("DomainPlayer")) {
            int n = 0;
            final CardCollectionView someCards = player.getLandsInPlay();
            final List<String> basic = MagicColor.Constant.BASIC_LANDS;

            for (int i = 0; i < basic.size(); i++) {
                if (!CardLists.getType(someCards, basic.get(i)).isEmpty()) {
                    n++;
                }
            }
            return doXMath(n, m, source, ctb);
        }

        if (value.contains("CardsInHand")) {
            return doXMath(player.getCardsIn(ZoneType.Hand).size(), m, source, ctb);
        }

        if (value.contains("CardsInLibrary")) {
            return doXMath(player.getCardsIn(ZoneType.Library).size(), m, source, ctb);
        }

        if (value.contains("CardsInGraveyard")) {
            return doXMath(player.getCardsIn(ZoneType.Graveyard).size(), m, source, ctb);
        }
        if (value.contains("LandsInGraveyard")) {
            return doXMath(CardLists.getType(player.getCardsIn(ZoneType.Graveyard), "Land").size(), m, source, ctb);
        }

        if (value.contains("CardsInPlay")) {
            return doXMath(player.getCardsIn(ZoneType.Battlefield).size(), m, source, ctb);
        }
        if (value.contains("CreaturesInPlay")) {
            return doXMath(player.getCreaturesInPlay().size(), m, source, ctb);
        }

        if (value.contains("StartingLife")) {
            return doXMath(player.getStartingLife(), m, source, ctb);
        }

        if (value.contains("LifeTotal")) {
            return doXMath(player.getLife(), m, source, ctb);
        }

        if (value.contains("LifeLostThisTurn")) {
            return doXMath(player.getLifeLostThisTurn(), m, source, ctb);
        }
        if (value.contains("LifeLostLastTurn")) {
            return doXMath(player.getLifeLostLastTurn(), m, source, ctb);
        }

        if (value.contains("LifeGainedThisTurn")) {
            return doXMath(player.getLifeGainedThisTurn(), m, source, ctb);
        }

        if (value.contains("LifeGainedByTeamThisTurn")) {
            return doXMath(player.getLifeGainedByTeamThisTurn(), m, source, ctb);
        }

        if (value.contains("LifeStartedThisTurnWith")) {
            return doXMath(player.getLifeStartedThisTurnWith(), m, source, ctb);
        }

        if (value.contains("PoisonCounters")) {
            return doXMath(player.getPoisonCounters(), m, source, ctb);
        }

        if (value.contains("TopOfLibraryCMC")) {
            return doXMath(Aggregates.sum(player.getCardsIn(ZoneType.Library, 1), CardPredicates.Accessors.fnGetCmc), m, source, ctb);
        }

        if (value.contains("LandsPlayed")) {
            return doXMath(player.getLandsPlayedThisTurn(), m, source, ctb);
        }

        if (value.contains("SpellsCastThisTurn")) {
            return doXMath(player.getSpellsCastThisTurn(), m, source, ctb);
        }

        if (value.contains("CardsDrawn")) {
            return doXMath(player.getNumDrawnThisTurn(), m, source, ctb);
        }

        if (value.contains("CardsDiscardedThisTurn")) {
            return doXMath(player.getNumDiscardedThisTurn(), m, source, ctb);
        }

        if (value.contains("AttackersDeclared")) {
            return doXMath(player.getCreaturesAttackedThisTurn().size(), m, source, ctb);
        }

        if (value.contains("DamageToOppsThisTurn")) {
            return doXMath(player.getOpponentsAssignedDamage(), m, source, ctb);
        }

        if (value.contains("NonCombatDamageDealtThisTurn")) {
            return doXMath(player.getAssignedDamage() - player.getAssignedCombatDamage(), m, source, ctb);
        }

        if (value.equals("OpponentsAttackedThisTurn")) {
            final Iterable<Player> opps = player.getAttackedPlayersMyTurn();
            return doXMath(opps == null ? 0 : Iterables.size(opps), m, source, ctb);
        }

        if (value.equals("OpponentsAttackedThisCombat")) {
            int amount = game.getCombat() == null ? 0 : game.getCombat().getAttackedOpponents(player).size();
            return doXMath(amount, m, source, ctb);
        }

        if (value.equals("DungeonsCompleted")) {
            return doXMath(player.getCompletedDungeons().size(), m, source, ctb);
        }
        if (value.startsWith("DungeonCompletedNamed")) {
            String [] full = value.split("_");
            String name = full[1];
            int completed = 0;
            List<Card> dungeons = player.getCompletedDungeons();
            for (Card c : dungeons) {
                if (c.getName().equals(name)) {
                    ++completed;
                }
            }
            return doXMath(completed, m, source, ctb);
        }
        if (value.equals("DifferentlyNamedDungeonsCompleted")) {
            int amount = 0;
            List<Card> dungeons = player.getCompletedDungeons();
            for (int i = 0; i < dungeons.size(); ++i) {
                Card d1 = dungeons.get(i);
                boolean hasSameName = false;
                for (int j = i - 1; j >= 0; --j) {
                    Card d2 = dungeons.get(j);
                    if (d1.getName().equals(d2.getName())) {
                        hasSameName = true;
                        break;
                    }
                }
                if (!hasSameName) {
                    ++amount;
                }
            }
            return doXMath(amount, m, source, ctb);
        }

        return doXMath(0, m, source, ctb);
    }

    /**
     * <p>
     * Parse player targeted X variables.
     * </p>
     *
     * @param objects
     *            a {@link java.util.ArrayList} object.
     * @param s
     *            a {@link java.lang.String} object.
     * @param source
     *            a {@link forge.game.card.Card} object.
     * @return a int.
     */
    public static int objectXCount(final List<?> objects, final String s, final Card source, CardTraitBase ctb) {
        if (objects.isEmpty()) {
            return 0;
        }

        if (s.startsWith("Valid")) {
            return handlePaid(Iterables.filter(objects, Card.class), s, source, ctb);
        }

        int n = s.startsWith("Amount") ? objects.size() : 0;
        return doXMath(n, CardFactoryUtil.extractOperators(s), source, ctb);
    }

    /**
     * <p>
     * handlePaid.
     * </p>
     *
     * @param paidList
     *            a {@link forge.game.card.CardCollectionView} object.
     * @param string
     *            a {@link java.lang.String} object.
     * @param source
     *            a {@link forge.game.card.Card} object.
     * @return a int.
     */
    public static int handlePaid(final Iterable<Card> paidList, final String string, final Card source, CardTraitBase ctb) {
        if (Iterables.isEmpty(paidList)) {
            if (string.contains(".")) {
                final String[] splitString = string.split("\\.", 2);
                return doXMath(0, splitString[1], source, ctb);
            } else {
                return 0;
            }
        }
        if (string.startsWith("Amount")) {
            int size = Iterables.size(paidList);
            if (string.contains(".")) {
                final String[] splitString = string.split("\\.", 2);
                return doXMath(size, splitString[1], source, ctb);
            } else {
                return size;
            }
        }

        if (string.startsWith("GreatestPower")) {
            return Aggregates.max(paidList, CardPredicates.Accessors.fnGetNetPower);
        }
        if (string.startsWith("GreatestToughness")) {
            return Aggregates.max(paidList, CardPredicates.Accessors.fnGetNetToughness);
        }

        if (string.startsWith("SumToughness")) {
            return Aggregates.sum(paidList, CardPredicates.Accessors.fnGetNetToughness);
        }

        if (string.startsWith("GreatestCMC")) {
            return Aggregates.max(paidList, CardPredicates.Accessors.fnGetCmc);
        }

        if (string.startsWith("DifferentCMC")) {
            final Set<Integer> diffCMC = new HashSet<>();
            for (final Card card : paidList) {
                diffCMC.add(card.getCMC());
            }
            return diffCMC.size();
        }

        if (string.startsWith("SumCMC")) {
            return Aggregates.sum(paidList, CardPredicates.Accessors.fnGetCmc);
        }

        if (string.startsWith("Valid")) {
            final String[] splitString = string.split("/", 2);
            String valid = splitString[0].substring(6);
            final List<Card> list = CardLists.getValidCardsAsList(paidList, valid, source.getController(), source, ctb);
            return doXMath(list.size(), splitString.length > 1 ? splitString[1] : null, source, ctb);
        }

        String filteredString = string;
        Iterable<Card> filteredList = paidList;
        final String[] filter = filteredString.split("_");

        if (string.startsWith("FilterControlledBy")) {
            final String pString = filter[0].substring(18);
            FCollectionView<Player> controllers = getDefinedPlayers(source, pString, ctb);
            filteredList = CardLists.filterControlledByAsList(filteredList, controllers);
            filteredString = TextUtil.fastReplace(filteredString, pString, "");
            filteredString = TextUtil.fastReplace(filteredString, "FilterControlledBy_", "");
        }

        int tot = 0;

        for (final Card c : filteredList) {
            tot += xCount(c, filteredString, ctb);
        }

        return tot;
    }

    private static CardCollectionView getCardListForXCount(final Card c, final Player cc, final String[] sq, CardTraitBase ctb) {
        final List<Player> opps = cc.getOpponents();
        CardCollection someCards = new CardCollection();
        final Game game = c.getGame();

        // Generic Zone-based counting
        // Count$QualityAndZones.Subquality

        // build a list of cards in each possible specified zone

        if (sq[0].contains("YouCtrl")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Battlefield));
        }

        if (sq[0].contains("InYourYard")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Graveyard));
        }

        if (sq[0].contains("InYourLibrary")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Library));
        }

        if (sq[0].contains("InYourHand")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Hand));
        }

        if (sq[0].contains("InYourSideboard")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Sideboard));
        }

        if (sq[0].contains("OppCtrl")) {
            for (final Player p : opps) {
                someCards.addAll(p.getZone(ZoneType.Battlefield).getCards());
            }
        }

        if (sq[0].contains("InOppYard")) {
            for (final Player p : opps) {
                someCards.addAll(p.getCardsIn(ZoneType.Graveyard));
            }
        }

        if (sq[0].contains("InOppHand")) {
            for (final Player p : opps) {
                someCards.addAll(p.getCardsIn(ZoneType.Hand));
            }
        }

        if (sq[0].contains("InChosenHand")) {
            if (c.hasChosenPlayer()) {
                someCards.addAll(c.getChosenPlayer().getCardsIn(ZoneType.Hand));
            }
        }

        if (sq[0].contains("InRememberedHand")) {
            if (c.getRemembered() != null) {
                for (final Object o : c.getRemembered()) {
                    if (o instanceof Player) {
                        Player remPlayer = (Player) o;
                        someCards.addAll(remPlayer.getCardsIn(ZoneType.Hand));
                    }
                }
            }
        }

        if (sq[0].contains("InChosenYard")) {
            if (c.hasChosenPlayer()) {
                someCards.addAll(c.getChosenPlayer().getCardsIn(ZoneType.Graveyard));
            }
        }

        if (sq[0].contains("OnBattlefield")) {
            someCards.addAll(game.getCardsIn(ZoneType.Battlefield));
        }

        if (sq[0].contains("InAllYards")) {
            someCards.addAll(game.getCardsIn(ZoneType.Graveyard));
        }

        if (sq[0].contains("SpellsOnStack")) {
            someCards.addAll(game.getCardsIn(ZoneType.Stack));
        }

        if (sq[0].contains("InAllHands")) {
            someCards.addAll(game.getCardsIn(ZoneType.Hand));
        }

        //  Count$InTargetedHand (targeted player's cards in hand)
        if (sq[0].contains("InTargetedHand")) {
            for (Player tgtP : getDefinedPlayers(c, "TargetedPlayer", ctb)) {
                someCards.addAll(tgtP.getCardsIn(ZoneType.Hand));
            }
        }

        //  Count$InTargetedLibrary (targeted player's cards in library)
        if (sq[0].contains("InTargetedLibrary")) {
            for (Player tgtP : getDefinedPlayers(c, "TargetedPlayer", ctb)) {
                someCards.addAll(tgtP.getCardsIn(ZoneType.Library));
            }
        }

        //  Count$InEnchantedHand (targeted player's cards in hand)
        if (sq[0].contains("InEnchantedHand")) {
            GameEntity o = c.getEntityAttachedTo();
            Player controller = null;
            if (o instanceof Card) {
                controller = ((Card) o).getController();
            }
            else {
                controller = (Player) o;
            }
            if (controller != null) {
                someCards.addAll(controller.getCardsIn(ZoneType.Hand));
            }
        }
        if (sq[0].contains("InEnchantedYard")) {
            GameEntity o = c.getEntityAttachedTo();
            Player controller = null;
            if (o instanceof Card) {
                controller = ((Card) o).getController();
            }
            else {
                controller = (Player) o;
            }
            if (controller != null) {
                someCards.addAll(controller.getCardsIn(ZoneType.Graveyard));
            }
        }

        // filter lists based on the specified quality

        // "Clerics you control" - Count$TypeYouCtrl.Cleric
        if (sq[0].contains("Type")) {
            someCards = CardLists.getType(someCards, sq[1]);
        }

        // "Named <CARDNAME> in all graveyards" - Count$NamedAllYards.<CARDNAME>

        if (sq[0].contains("Named")) {
            if (sq[1].equals("CARDNAME")) {
                sq[1] = c.getName();
            }
            someCards = CardLists.filter(someCards, CardPredicates.nameEquals(sq[1]));
        }

        // Refined qualities

        // "Untapped Lands" - Count$UntappedTypeYouCtrl.Land
        // if (sq[0].contains("Untapped")) { someCards = CardLists.filter(someCards, Presets.UNTAPPED); }

        // if (sq[0].contains("Tapped")) { someCards = CardLists.filter(someCards, Presets.TAPPED); }

//        String sq0 = sq[0].toLowerCase();
//        for (String color : MagicColor.Constant.ONLY_COLORS) {
//            if (sq0.contains(color))
//                someCards = someCards.filter(CardListFilter.WHITE);
//        }
        // "White Creatures" - Count$WhiteTypeYouCtrl.Creature
        // if (sq[0].contains("White")) someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.WHITE));
        // if (sq[0].contains("Blue"))  someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.BLUE));
        // if (sq[0].contains("Black")) someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.BLACK));
        // if (sq[0].contains("Red"))   someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.RED));
        // if (sq[0].contains("Green")) someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.GREEN));

        if (sq[0].contains("Multicolor")) {
            someCards = CardLists.filter(someCards, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    return c.getColor().isMulticolor();
                }
            });
        }

        if (sq[0].contains("Monocolor")) {
            someCards = CardLists.filter(someCards, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    return c.getColor().isMonoColor();
                }
            });
        }
        return someCards;
    }

    public static CardCollection getPaidCards(CardTraitBase sa, String defined) {
        CardCollection list = null;
        if (sa instanceof SpellAbility) {
            SpellAbility root = ((SpellAbility)sa).getRootAbility();
            // TODO do we really need these checks?
            if (defined.startsWith("SacrificedCards")) {
                list = root.getPaidList("SacrificedCards");
            } else if (defined.startsWith("Sacrificed")) {
                list = root.getPaidList("Sacrificed");
            } else if (defined.startsWith("Revealed")) {
                list = root.getPaidList("Revealed");
            } else if (defined.startsWith("DiscardedCards")) {
                list = root.getPaidList("DiscardedCards");
            } else if (defined.startsWith("Discarded")) {
                list = root.getPaidList("Discarded");
            } else if (defined.startsWith("ExiledCards")) {
                list = root.getPaidList("ExiledCards");
            } else if (defined.startsWith("Exiled")) {
                list = root.getPaidList("Exiled");
            } else if (defined.startsWith("Milled")) {
                list = root.getPaidList("Milled");
            } else if (defined.startsWith("TappedCards")) {
                list = root.getPaidList("TappedCards");
            } else if (defined.startsWith("Tapped")) {
                list = root.getPaidList("Tapped");
            } else if (defined.startsWith("UntappedCards")) {
                list = root.getPaidList("UntappedCards");
            } else if (defined.startsWith("Untapped")) {
                list = root.getPaidList("Untapped");
            }
        }
        return list;
    }

    public static int getNumberOfTypes(final Card card) {
        EnumSet<CardType.CoreType> types = EnumSet.noneOf(CardType.CoreType.class);
        Iterables.addAll(types, card.getType().getCoreTypes());
        return types.size();
    }

    public static int getCardTypesFromList(final CardCollectionView list) {
        EnumSet<CardType.CoreType> types = EnumSet.noneOf(CardType.CoreType.class);
        for (Card c1 : list) {
            Iterables.addAll(types, c1.getType().getCoreTypes());
        }
        return types.size();
    }
}
