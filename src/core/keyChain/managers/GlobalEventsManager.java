package core.keyChain.managers;

import java.util.Set;
import java.util.logging.Logger;

import javax.swing.JFrame;

import org.jnativehook.NativeHookException;
import org.simplenativehooks.events.NativeKeyEvent;
import org.simplenativehooks.listeners.AbstractGlobalKeyListener;
import org.simplenativehooks.utilities.Function;

import core.config.Config;
import core.controller.CoreProvider;
import core.keyChain.ActivationEvent;
import core.keyChain.KeyStroke;
import core.keyChain.TaskActivation;
import core.userDefinedTask.TaskGroup;
import core.userDefinedTask.UserDefinedAction;
import core.userDefinedTask.internals.ActionExecutor;
import core.userDefinedTask.internals.SharedVariablesPubSubManager;
import core.userDefinedTask.internals.SharedVariablesSubscriber;
import core.userDefinedTask.internals.SharedVariablesSubscription;
import globalListener.GlobalListenerFactory;
import utilities.StringUtilities;

public final class GlobalEventsManager {

	private static final Logger LOGGER = Logger.getLogger(GlobalEventsManager.class.getName());

	private final Config config;
	CoreProvider coreProvider;
	private final ActionExecutor actionExecutor;
	/**
	 * This function is the precondition to executing any task.
	 * It is evaluated every time the manager considers executing any task.
	 * If it evaluates to true, the task will not be executed.
	 */
	private Function<Void, Boolean> disablingFunction;
	private final ActivationEventManager taskActivationManager;

	@SuppressWarnings("unused")
	private TaskGroup currentTaskGroup;

	public GlobalEventsManager(Config config, CoreProvider coreProvider, ActionExecutor actionExecutor) {
		this.config = config;
		this.coreProvider = coreProvider;
		this.actionExecutor = actionExecutor;

		this.disablingFunction = Function.falseFunction();

		this.taskActivationManager = new AggregateActivationEventManager(config,
				new KeyChainManager(config),
				new KeySequenceManager(config),
				new PhraseManager(config),
				new MouseGestureManager(config),
				new SharedVariablesManager(config),
				new GlobalKeyActionManager(config));
	}

	public void startGlobalListener() throws NativeHookException {
		AbstractGlobalKeyListener keyListener = GlobalListenerFactory.of().createGlobalKeyListener();
		keyListener.setKeyPressed(new Function<NativeKeyEvent, Boolean>() {
			@Override
			public Boolean apply(NativeKeyEvent r) {
				KeyStroke stroke = KeyStroke.of(r);
				if (!shouldDelegate(stroke)) {
					return true;
				}

				Set<UserDefinedAction> actions = taskActivationManager.onActivationEvent(ActivationEvent.of(stroke));
				actionExecutor.startExecutingActions(actions);
				return true;
			}
		});

		keyListener.setKeyReleased(new Function<NativeKeyEvent, Boolean>() {
			@Override
			public Boolean apply(NativeKeyEvent r) {
				KeyStroke stroke = KeyStroke.of(r);
				if (!shouldDelegate(stroke)) {
					return true;
				}

				Set<UserDefinedAction> actions = taskActivationManager.onActivationEvent(ActivationEvent.of(stroke));
				actionExecutor.startExecutingActions(actions);
				return true;
			}
		});

		SharedVariablesPubSubManager.get().addSubscriber(SharedVariablesSubscriber.of(SharedVariablesSubscription.forAll(), e -> {
			Set<UserDefinedAction> actions = taskActivationManager.onActivationEvent(ActivationEvent.of(e));
			actionExecutor.startExecutingActions(actions);
		}));

		taskActivationManager.startListening();
		keyListener.startListening();
	}

	public void setDisablingFunction(Function<Void, Boolean> disablingFunction) {
		this.disablingFunction = disablingFunction;
	}

	public void setCurrentTaskGroup(TaskGroup currentTaskGroup) {
		this.currentTaskGroup = currentTaskGroup;
	}

	/**
	 * Given a new key code coming in, consider whether we should delegate
	 * to the {@link KeyStrokeManager}, or take actions and terminate.
	 *
	 * @param keyCode new keyCode coming in
	 * @return if we should continue delegating this to the managers.
	 */
	private boolean shouldDelegate(KeyStroke stroke) {
		if (stroke.getKey() == Config.HALT_TASK && config.isEnabledHaltingKeyPressed()) {
			taskActivationManager.clear();
			actionExecutor.haltAllTasks();
			return false;
		}

		return !disablingFunction.apply(null);
	}

	/**
	 * Map all key chains of the current task to the action. Kick out all colliding tasks.
	 * @param action action to register.
	 * @return List of currently registered tasks that collide with this newly registered task
	 */
	public Set<UserDefinedAction> registerTask(UserDefinedAction action) {
		return taskActivationManager.registerAction(action);
	}

	/**
	 * Unregister the action, then modify the action activation to be the new activation, and finally register the modified action.
	 * This kicks out all other actions that collide with the action provided.
	 *
	 * @param action action to be re-registered with new activation.
	 * @param newActivation new activation to be associated with the action.
	 * @return set of actions that collide with this action.
	 */
	public Set<UserDefinedAction> reRegisterTask(UserDefinedAction action, TaskActivation newActivation) {
		unregisterTask(action);
		action.setActivation(newActivation);
		return registerTask(action);
	}

	/**
	 * Remove all bindings to the task's activation.
	 * @param action action whose activation will be removed.
	 * @return if all activations are removed.
	 */
	public boolean unregisterTask(UserDefinedAction action) {
		taskActivationManager.unRegisterAction(action);
		return true;
	}

	/**
	 * @param action
	 * @return return set of actions that collide with this action, excluding the input task.
	 */
	public Set<UserDefinedAction> isTaskRegistered(UserDefinedAction action) {
		Set<UserDefinedAction> output = isActivationRegistered(action.getActivation());
		output.remove(action);
		return output;
	}

	/**
	 * Check if an activation is already registered.
	 * @param activation
	 * @return return set of actions that collide with this activation.
	 */
	public Set<UserDefinedAction> isActivationRegistered(TaskActivation activation) {
		return taskActivationManager.collision(activation);
	}

	/**
	 * Show a short notice that collision occurred.
	 *
	 * @param parent parent frame to show the notice in (null if there is none)
	 * @param collisions set of colliding tasks.
	 */
	public static void showCollisionWarning(JFrame parent, Set<UserDefinedAction> collisions) {
		String taskNames = StringUtilities.join(new Function<UserDefinedAction, String>() {
			@Override
			public String apply(UserDefinedAction d) {
				return '\'' + d.getName() + '\'';
			}}.map(collisions), ", ");

		LOGGER.warning(
				"Newly registered keychains "
				+ "will collide with previously registered task(s) " + taskNames + "\n"
				+ "You cannot assign this key chain unless you remove the conflicting key chain...");
	}
}
