package io.edgeai.app.controller;

import io.edgeai.app.service.DataStoreService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/data-store")
public class DataStoreController {
    private final DataStoreService service;
    public DataStoreController(DataStoreService service){this.service=service;}
    @GetMapping public Map<String,Object> catalog(){return service.catalog();}
    @GetMapping("/sensors/{uid}/{property}/history") public Map<String,Object> history(@PathVariable UUID uid,@PathVariable String property,
        @RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(defaultValue="1000") int limit,@RequestParam(required=false) String cursor){return service.history(uid,property,from,to,limit,cursor);}
}
