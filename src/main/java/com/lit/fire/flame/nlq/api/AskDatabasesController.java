package com.lit.fire.flame.nlq.api;

import com.lit.fire.flame.nlq.connection.DatasourceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Discovery endpoint for the server-side datasource registry: {@code GET /api/ask/databases}.
 *
 * <p>Lists the databases AuraMath loaded from the host credential file ({@code ~/config.secrets}) so an
 * operator or client can see which names are available to target in an Ask — <b>without</b> any
 * credentials. Each entry carries only the logical name, the resolved driver alias, and a
 * credential-free host[:port] (see {@link DatasourceRegistry#describe()}).
 */
@RestController
@RequestMapping("/api/ask")
public class AskDatabasesController {

    private final DatasourceRegistry registry;

    public AskDatabasesController(DatasourceRegistry registry) {
        this.registry = registry;
    }

    /** The registered databases available to target, credential-free. */
    @GetMapping("/databases")
    public List<DatasourceRegistry.DatasourceInfo> databases() {
        return registry.describe();
    }
}
