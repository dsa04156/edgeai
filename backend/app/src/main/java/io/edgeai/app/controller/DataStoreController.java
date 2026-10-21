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
        @RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(defaultValue="1000") int limit,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="asc") String order){return service.history(uid,property,from,to,limit,cursor,order);}
    @GetMapping("/sensors/{uid}/{property}/chart") public Map<String,Object> chart(@PathVariable UUID uid,@PathVariable String property,
        @RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(required=false) Integer points,@RequestParam(required=false) Integer window){return service.chart(uid,property,from,to,points,window);}
}
