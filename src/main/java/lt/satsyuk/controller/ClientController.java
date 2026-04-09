package lt.satsyuk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.dto.RequestAcceptedResponse;
import lt.satsyuk.service.ClientService;
import lt.satsyuk.service.RequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor
@Tag(name = "Clients", description = "Client management")
@SecurityRequirement(name = "bearerAuth")
public class ClientController {

    private final ClientService clientService;
    private final RequestService requestService;

    @PostMapping
    @PreAuthorize("hasRole('CLIENT_CREATE')")
    @Operation(summary = "Create client", description = "Creates an asynchronous client creation request.")
    @ApiResponse(responseCode = "202", description = "Client creation request accepted",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AppResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "401", description = "Unauthorized",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "403", description = "Forbidden",
            content = @Content(mediaType = "application/json"))
    public Mono<ResponseEntity<AppResponse<RequestAcceptedResponse>>> create(
            @Valid @RequestBody CreateClientRequest req) {
        return requestService.submitClientCreateRequest(req)
                .map(response -> ResponseEntity.status(HttpStatus.ACCEPTED).body(AppResponse.ok(response)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLIENT_GET')")
    @Operation(summary = "Get client", description = "Returns client by id.")
    @ApiResponse(responseCode = "200", description = "Client found",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AppResponse.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "403", description = "Forbidden",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/json"))
    public Mono<AppResponse<ClientResponse>> get(@PathVariable("id") Long id) {
        return clientService.get(id)
                .map(AppResponse::ok);
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('CLIENT_SEARCH')")
    @Operation(summary = "Search clients", description = "Searches clients by first name or last name. Query minimum length is validated by server, and response size is capped by app.clients.search.max-results.")
    @ApiResponse(responseCode = "200", description = "Search completed",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AppResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid query",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "401", description = "Unauthorized",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "403", description = "Forbidden",
            content = @Content(mediaType = "application/json"))
    public Mono<AppResponse<List<ClientResponse>>> search(@RequestParam("q") String query) {
        return clientService.searchByNameOrSurname(query)
                .map(AppResponse::ok);
    }
}

