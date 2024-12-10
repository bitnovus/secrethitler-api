package de.secrethitler.api.controllers;

import com.github.collinalpert.java2db.database.DBConnection;
import com.github.collinalpert.java2db.queries.OrderTypes;
import de.secrethitler.api.entities.LinkedUserGameRole;
import de.secrethitler.api.entities.Round;
import de.secrethitler.api.exceptions.EmptyOptionalException;
import de.secrethitler.api.modules.ElectionTrackerModule;
import de.secrethitler.api.modules.EligibilityModule;
import de.secrethitler.api.modules.NumberModule;
import de.secrethitler.api.modules.PusherModule;
import de.secrethitler.api.services.GameService;
import de.secrethitler.api.services.LinkedUserGameRoleService;
import de.secrethitler.api.services.RoundService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * @author Collin Alpert
 */
@RestController
@RequestMapping("/round")
@CrossOrigin(allowCredentials = "true", origins = "localhost")
public class RoundController {

	private final GameService gameService;
	private final PusherModule pusherModule;
	private final LinkedUserGameRoleService linkedUserGameRoleService;
	private final RoundService roundService;
	private final EligibilityModule eligibilityModule;
	private final NumberModule numberModule;
	private final ElectionTrackerModule electionTrackerModule;

	public RoundController(GameService gameService,
						   PusherModule pusherModule,
						   LinkedUserGameRoleService linkedUserGameRoleService,
						   RoundService roundService,
						   EligibilityModule eligibilityModule,
						   NumberModule numberModule,
						   ElectionTrackerModule electionTrackerModule) {
		this.gameService = gameService;
		this.pusherModule = pusherModule;
		this.linkedUserGameRoleService = linkedUserGameRoleService;
		this.roundService = roundService;
		this.eligibilityModule = eligibilityModule;
		this.numberModule = numberModule;
		this.electionTrackerModule = electionTrackerModule;
	}

	/**
	 * Brings a game to the next round. Sends a Pusher event to the new president with the eligible chancellors he can nominate.
	 *
	 * @param requestBody The request's body, containing the channelName of the game to create a new round for.
	 * @return The id of the president in the new round.
	 * @throws SQLException The exception which can occur when interchanging with the database.
	 */
	@PostMapping(value = "/next", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> nextRound(@RequestBody Map<String, Object> requestBody, @RequestHeader(HttpHeaders.AUTHORIZATION) String base64Token) throws SQLException {
		if (!requestBody.containsKey("channelName")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "channelName is missing."));
		}

		if (!requestBody.containsKey("userId")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "userId is missing."));
		}

		var channelName = (String) requestBody.get("channelName");
		var userId = this.numberModule.getAsLong(requestBody.get("userId"));
		if (!this.linkedUserGameRoleService.hasValidToken(userId, base64Token)) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Invalid authorization."));
		}

		long gameId = this.gameService.getIdByChannelName(channelName).orElseThrow(() -> new EmptyOptionalException("No game was found for the given channelName."));

		long nextPresidentId;
		try (var connection = new DBConnection()) {
			nextPresidentId = connection.callFunction(long.class, "GetNextPresidentId", gameId).orElseThrow(() -> new EmptyOptionalException("Could not get next president."));
		}

		this.pusherModule.trigger(channelName, "nextRound", Collections.singletonMap("presidentId", nextPresidentId));

		var currentRoundOptional = this.roundService.getCurrentRound(gameId);
		var players = this.linkedUserGameRoleService.getMultiple(x -> x.getGameId() == gameId && !x.isExecuted()).orderBy(LinkedUserGameRole::getSequenceNumber).toList();
		var electableChancellors = players.stream().filter(x -> x.getId() != nextPresidentId);
		if (currentRoundOptional.isPresent()) {
			electableChancellors = electableChancellors.filter(x -> x.getId() != currentRoundOptional.get().getPresidentId() && (currentRoundOptional.get().getChancellorId() == null || x.getId() != currentRoundOptional.get().getChancellorId()));
		}

		var electableChancellorIds = electableChancellors.map(LinkedUserGameRole::getId).collect(Collectors.toList());
		this.pusherModule.trigger(String.format("private-%d", nextPresidentId), "notifyPresident", Collections.singletonMap("electable", electableChancellorIds));

		var newRoundSequenceNumber = currentRoundOptional.map(Round::getSequenceNumber).orElse(0) + 1;
		this.roundService.create(new Round(newRoundSequenceNumber, gameId, nextPresidentId));

		return ResponseEntity.ok(Collections.singletonMap("presidentId", nextPresidentId));
	}

	/**
	 * Creates a special election round. This is an executive action.
	 *
	 * @param requestBody The request's body, containing the channelName of the game and the id of the player who was chosen to be president in this special round.
	 * @return A successful 200 HTTP response.
	 * @throws SQLException The exception which can occur when interchanging with the database.
	 */
	@PostMapping(value = "/special-election", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> specialElection(@RequestBody Map<String, Object> requestBody, @RequestHeader(HttpHeaders.AUTHORIZATION) String base64Token) throws SQLException {
		if (!requestBody.containsKey("channelName")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "channelName is missing."));
		}

		if (!requestBody.containsKey("nextPresidentId")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "nextPresidentId is missing."));
		}

		if (!requestBody.containsKey("userId")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "userId is missing."));
		}

		var channelName = (String) requestBody.get("channelName");
		var nextPresidentId = this.numberModule.getAsLong(requestBody.get("nextPresidentId"));
		var userId = this.numberModule.getAsLong(requestBody.get("userId"));
		if (!this.linkedUserGameRoleService.hasValidToken(userId, base64Token)) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Invalid authorization."));
		}

		if (!this.linkedUserGameRoleService.any(x -> x.getId() == nextPresidentId)) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "No user was found to be the next president."));
		}

		var game = this.gameService.getByChannelName(channelName).orElseThrow(() -> new EmptyOptionalException(String.format("No game was found for channelName '%s'.", channelName)));
		var gameId = game.getId();

		if (!this.eligibilityModule.isSpecialElectionEligible(game)) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Special election is not available yet."));
		}

		var currentRoundSequenceNumber = this.roundService.getMultiple(x -> x.getGameId() == gameId).orderBy(OrderTypes.DESCENDING, Round::getSequenceNumber).limit(1).project(Round::getSequenceNumber).first().orElseThrow(() -> new EmptyOptionalException("No round was found in the current game."));
		this.roundService.create(new Round(currentRoundSequenceNumber + 1, gameId, nextPresidentId, true));
		this.pusherModule.trigger(channelName, "nextRound", Collections.singletonMap("presidentId", nextPresidentId));

		return ResponseEntity.ok(Collections.emptyMap());
	}

	@PostMapping(value = "/request-veto", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> requestVeto(@RequestBody Map<String, Object> requestBody, @RequestHeader(HttpHeaders.AUTHORIZATION) String base64Token) {
		if (!requestBody.containsKey("channelName")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "channelName is missing."));
		}

		if (!requestBody.containsKey("userId")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "userId is missing."));
		}

		var channelName = (String) requestBody.get("channelName");
		var userId = this.numberModule.getAsLong(requestBody.get("userId"));

		if (!this.linkedUserGameRoleService.hasValidToken(userId, base64Token)) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Invalid authorization."));
		}

		var gameId = this.gameService.getIdByChannelName(channelName).orElseThrow(() -> new EmptyOptionalException(String.format("No game was found for channelName '%s'.", channelName)));
		if (!this.eligibilityModule.isVetoEligible(gameId)) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Veto is not allowed in the current game state."));
		}

		var currentRound = this.roundService.getCurrentRound(gameId).orElseThrow(() -> new EmptyOptionalException(String.format("No round was found for the channelName '%s'.", channelName)));
		if (currentRound.getChancellorId() == null || currentRound.getChancellorId() != userId) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Only the chancellor can request a veto."));
		}

		this.pusherModule.trigger(String.format("private-%d", currentRound.getPresidentId()), "requestVeto", Collections.emptyList());

		return ResponseEntity.ok(Collections.emptyMap());
	}

	@PostMapping(value = "/veto", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> useVeto(@RequestBody Map<String, Object> requestBody, @RequestHeader(HttpHeaders.AUTHORIZATION) String base64Token) throws InterruptedException, ExecutionException, SQLException {
		if (!requestBody.containsKey("channelName")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "channelName is missing."));
		}

		if (!requestBody.containsKey("accepted")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "accepted is missing."));
		}

		if (!requestBody.containsKey("userId")) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "userId is missing."));
		}

		var channelName = (String) requestBody.get("channelName");
		var accepted = (boolean) requestBody.get("accepted");
		var userId = this.numberModule.getAsLong(requestBody.get("userId"));

		if (!this.linkedUserGameRoleService.hasValidToken(userId, base64Token)) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Invalid authorization."));
		}

		var gameId = this.gameService.getIdByChannelName(channelName).orElseThrow(() -> new EmptyOptionalException(String.format("No game was found for channelName '%s'.", channelName)));
		if (!this.eligibilityModule.isVetoEligible(gameId)) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Veto is not allowed in the current game state."));
		}

		var currentRound = this.roundService.getCurrentRound(gameId).orElseThrow(() -> new EmptyOptionalException(String.format("No round was found for the channelName '%s'.", channelName)));
		if (currentRound.getPresidentId() != userId) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Only the president can accept or deny."));
		}

		if (accepted) {
			this.pusherModule.trigger(channelName, "vetoAccepted", Collections.emptyList());
			this.electionTrackerModule.advance(channelName, gameId, currentRound.getId());
		} else {
			this.pusherModule.trigger(String.format("private-%d", currentRound.getChancellorId()), "vetoDenied", Collections.emptyList());
		}

		return ResponseEntity.ok(Collections.emptyMap());
	}

}
