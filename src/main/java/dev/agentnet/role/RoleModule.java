package dev.agentnet.role;

import dev.agentnet.bus.Message;

/**
 * The behavior slot for one role. Every method runs on the main client thread and
 * does nothing by default, so a module only implements what it needs.
 *
 * <p>Lifecycle: {@code onStart} when the role is assigned (or the agent comes online
 * with it), {@code tick} 20x/second while online, {@code onStop} when the role changes
 * or the agent leaves.
 */
public interface RoleModule {
    /** Fighting someone right now (eating waits). */
    default boolean fighting() { return false; }

    /** Nothing to do right now (an idle agent waits at the base's hub). */
    default boolean idle() { return false; }

    default void onStart(AgentHandle agent) {}

    default void tick(AgentHandle agent) {}

    default void onStop(AgentHandle agent) {}

    /** Another agent (or you) sent this agent a message over the bus. */
    default void onMessage(AgentHandle agent, Message message) {}

    /** A command like /agent mine or /agent goto targeted this agent. */
    default void onTask(AgentHandle agent, String task) {}

    /** One line about what the module is doing, for the master screen ("" = nothing to say). */
    default String status() { return ""; }
}
