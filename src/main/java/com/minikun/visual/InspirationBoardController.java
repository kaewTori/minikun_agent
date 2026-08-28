package com.minikun.visual;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/personal/inspiration-boards")
public final class InspirationBoardController {
    private final InspirationBoardService service; private final String token;
    public InspirationBoardController(InspirationBoardService service, String token) {
        this.service=service; this.token=token==null?"":token.trim();
    }
    @GetMapping public List<InspirationBoard> list(@RequestParam(name="owner_id",defaultValue="default") String owner,
            @RequestParam(defaultValue="50") int limit, @RequestHeader(value="X-Minikun-Personal-Token",required=false) String supplied) {
        authorize(supplied); return service.list(owner,limit);
    }
    @PostMapping public InspirationBoard create(@RequestBody BoardRequest request,
            @RequestHeader(value="X-Minikun-Personal-Token",required=false) String supplied) {
        authorize(supplied); return service.create(request.owner_id(),request.title());
    }
    @DeleteMapping("/{boardId}") public void delete(@PathVariable UUID boardId,
            @RequestParam(name="owner_id",defaultValue="default") String owner,
            @RequestHeader(value="X-Minikun-Personal-Token",required=false) String supplied) {
        authorize(supplied); if(!service.delete(owner,boardId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    @GetMapping("/{boardId}/items") public List<InspirationBoardItem> items(@PathVariable UUID boardId,
            @RequestParam(name="owner_id",defaultValue="default") String owner,@RequestParam(defaultValue="100") int limit,
            @RequestHeader(value="X-Minikun-Personal-Token",required=false) String supplied) {
        authorize(supplied); return service.items(owner,boardId,limit);
    }
    @PostMapping("/{boardId}/items") public InspirationBoardItem add(@PathVariable UUID boardId,
            @RequestBody ItemRequest request,@RequestHeader(value="X-Minikun-Personal-Token",required=false) String supplied) {
        authorize(supplied); return service.add(request.owner_id(),boardId,request.image_url(),request.source_url(),
                request.title(),request.description(),request.origin());
    }
    @DeleteMapping("/{boardId}/items/{itemId}") public void remove(@PathVariable UUID boardId,@PathVariable UUID itemId,
            @RequestParam(name="owner_id",defaultValue="default") String owner,
            @RequestHeader(value="X-Minikun-Personal-Token",required=false) String supplied) {
        authorize(supplied); if(!service.remove(owner,boardId,itemId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private void authorize(String supplied){ if(!token.isBlank()&&!token.equals(supplied)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"personal token is invalid"); }
    public record BoardRequest(String owner_id,String title){}
    public record ItemRequest(String owner_id,String image_url,String source_url,String title,String description,String origin){}
}
