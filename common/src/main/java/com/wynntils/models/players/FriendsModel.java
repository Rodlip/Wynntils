/*
 * Copyright © Wynntils 2023.
 * This file is released under AGPLv3. See LICENSE for full license details.
 */
package com.wynntils.models.players;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Model;
import com.wynntils.core.components.Models;
import com.wynntils.handlers.chat.event.ChatMessageReceivedEvent;
import com.wynntils.handlers.chat.type.MessageType;
import com.wynntils.models.players.event.FriendsEvent;
import com.wynntils.models.players.event.HadesRelationsUpdateEvent;
import com.wynntils.models.players.hades.event.HadesEvent;
import com.wynntils.models.worlds.WorldStateModel;
import com.wynntils.models.worlds.event.WorldStateEvent;
import com.wynntils.models.worlds.type.WorldState;
import com.wynntils.utils.mc.ComponentUtils;
import com.wynntils.utils.mc.McUtils;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class FriendsModel extends Model {
    // region Friend Regexes
    /*
    Regexes should be named with this format:
    FRIEND_ACTION_(DETAIL)
    where:
    FRIEND should be the first word
    ACTION should be something like ADD, LIST, etc.
    DETAIL (optional) should be a descriptor if necessary
     */
    private static final Pattern FRIEND_LIST = Pattern.compile(".+'s? friends \\(.+\\): (.*)");
    private static final Pattern FRIEND_LIST_FAIL_1 = Pattern.compile("§eWe couldn't find any friends\\.");
    private static final Pattern FRIEND_LIST_FAIL_2 = Pattern.compile("§eTry typing §r§6/friend add Username§r§e!");
    private static final Pattern FRIEND_REMOVE_MESSAGE_PATTERN =
            Pattern.compile("§e(.+) has been removed from your friends!");
    private static final Pattern FRIEND_ADD_MESSAGE_PATTERN = Pattern.compile("§e(.+) has been added to your friends!");

    private static final Pattern JOIN_PATTERN = Pattern.compile(
            "(?:§a|§r§7)(?:§o)?(.+)§r(?:§2|§8(?:§o)?) has logged into server §r(?:§a|§7(?:§o)?)(?<server>.+)§r(?:§2|§8(?:§o)?) as (?:§r§a|§r§7(?:§o)?)an? (?<class>.+)");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("(?:§a|§r§7)(.+) left the game\\.");
    // endregion

    private boolean expectingFriendMessage = false;
    private long lastFriendRequest = 0;

    private Set<String> friends;

    public FriendsModel(WorldStateModel worldStateModel) {
        super(List.of(worldStateModel));
        resetData();
    }

    @SubscribeEvent
    public void onAuth(HadesEvent.Authenticated event) {
        if (!Models.WorldState.onWorld()) return;

        requestData();
    }

    @SubscribeEvent
    public void onWorldStateChange(WorldStateEvent event) {
        if (event.getNewState() == WorldState.WORLD) {
            requestData();
        } else {
            resetData();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChatReceived(ChatMessageReceivedEvent event) {
        if (event.getMessageType() != MessageType.FOREGROUND) return;

        String coded = event.getOriginalCodedMessage();
        String unformatted = ComponentUtils.stripFormatting(coded);

        Matcher joinMatcher = JOIN_PATTERN.matcher(coded);
        if (joinMatcher.matches()) {
            WynntilsMod.postEvent(new FriendsEvent.Joined(joinMatcher.group(1)));
        } else {
            Matcher leaveMatcher = LEAVE_PATTERN.matcher(coded);
            if (leaveMatcher.matches()) {
                WynntilsMod.postEvent(new FriendsEvent.Left(leaveMatcher.group(1)));
            }
        }

        if (tryParseFriendMessages(coded)) {
            return;
        }

        if (expectingFriendMessage) {
            if (tryParseFriendList(unformatted) || tryParseNoFriendList(coded)) {
                event.setCanceled(true);
                expectingFriendMessage = false;
                return;
            }

            // Skip first message of two, but still expect more messages
            if (FRIEND_LIST_FAIL_1.matcher(coded).matches()) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private boolean tryParseNoFriendList(String coded) {
        if (FRIEND_LIST_FAIL_2.matcher(coded).matches()) {
            WynntilsMod.info("Friend list is empty.");
            return true;
        }

        return false;
    }

    private boolean tryParseFriendMessages(String coded) {
        Matcher matcher = FRIEND_REMOVE_MESSAGE_PATTERN.matcher(coded);
        if (matcher.matches()) {
            String player = matcher.group(1);

            WynntilsMod.info("Player has removed friend: " + player);

            friends.remove(player);
            WynntilsMod.postEvent(new HadesRelationsUpdateEvent.FriendList(
                    Set.of(player), HadesRelationsUpdateEvent.ChangeType.REMOVE));
            WynntilsMod.postEvent(new FriendsEvent.Removed(player));
            return true;
        }

        matcher = FRIEND_ADD_MESSAGE_PATTERN.matcher(coded);
        if (matcher.matches()) {
            String player = matcher.group(1);

            WynntilsMod.info("Player has added friend: " + player);

            friends.add(player);
            WynntilsMod.postEvent(
                    new HadesRelationsUpdateEvent.FriendList(Set.of(player), HadesRelationsUpdateEvent.ChangeType.ADD));
            WynntilsMod.postEvent(new FriendsEvent.Added(player));
            return true;
        }

        return false;
    }

    private boolean tryParseFriendList(String unformatted) {
        Matcher matcher = FRIEND_LIST.matcher(unformatted);
        if (!matcher.matches()) return false;

        String[] friendList = matcher.group(1).split(", ");

        friends = Arrays.stream(friendList).collect(Collectors.toSet());
        WynntilsMod.postEvent(
                new HadesRelationsUpdateEvent.FriendList(friends, HadesRelationsUpdateEvent.ChangeType.RELOAD));
        WynntilsMod.postEvent(new FriendsEvent.Listed());

        WynntilsMod.info("Successfully updated friend list, user has " + friendList.length + " friends.");
        return true;
    }

    private void resetData() {
        friends = new HashSet<>();

        WynntilsMod.postEvent(
                new HadesRelationsUpdateEvent.FriendList(friends, HadesRelationsUpdateEvent.ChangeType.RELOAD));
    }

    /**
     * Sends "/friend list" to Wynncraft and waits for the response.
     * (!) Skips if the last request was less than 250ms ago.
     * When the response is received, friends will be updated.
     */
    public void requestData() {
        if (McUtils.player() == null) return;

        if (System.currentTimeMillis() - lastFriendRequest < 250) {
            WynntilsMod.info("Skipping friend list request because it was requested less than 250ms ago.");
            return;
        }

        expectingFriendMessage = true;
        lastFriendRequest = System.currentTimeMillis();
        McUtils.sendCommand("friend list");
        WynntilsMod.info("Requested friend list from Wynncraft.");
    }

    public boolean isFriend(String playerName) {
        return friends.contains(playerName);
    }

    public Set<String> getFriends() {
        return friends;
    }
}
