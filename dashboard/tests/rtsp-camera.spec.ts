import { test, expect } from "@playwright/test";

const uid="11111111-1111-4111-8111-111111111111";
test("camera preview proxy permits only same-origin GET with an explicit UID",async({request})=>{
 const path=`/api/control-plane/managed-devices/camera/preview?uid=${uid}`;
 expect((await request.get(path)).status()).toBe(503);
 expect((await request.get(path,{headers:{Origin:"https://other.invalid"}})).status()).toBe(403);
 expect((await request.get("/api/control-plane/managed-devices/camera/preview")).status()).toBe(400);
 expect((await request.post(path)).status()).toBe(404);
});
test("registers an anonymous RTSP camera and uses a fixed connection property",async({page})=>{
 let saved: unknown;
 await page.route("**/api/health",r=>r.fulfill({json:{status:"UP"}}));
 await page.route("**/api/control-plane/**",r=>{
  const p=new URL(r.request().url()).pathname.split("/api/control-plane/")[1];
  if(p==="infrastructure")return r.fulfill({json:{fetchedAt:new Date().toISOString(),nodesStatus:"AVAILABLE",sensorsStatus:"AVAILABLE",nodes:[{name:"edge-a",kind:"EDGE_DEVICE",status:"READY"}],sensors:[]}});
  if(p==="csrf")return r.fulfill({json:{token:"fixture"}});
  if(p==="managed-devices"&&r.request().method()==="POST"){saved=r.request().postDataJSON();return r.fulfill({status:201,json:{id:"camera-01",uid}})}
  return r.fulfill({json:{items:[],nextOffset:null}});
 });
 await page.goto("/devices?view=sensors");await page.getByRole("button",{name:"새 장치 등록",exact:true}).click();
 const dialog=page.getByRole("dialog",{name:"센서 등록",exact:true});
 await dialog.getByLabel("프로토콜",{exact:true}).selectOption("rtsp");
 await dialog.getByLabel("센서 이름",{exact:true}).fill("camera-01");
 await dialog.getByLabel("RTSP 주소",{exact:true}).fill("rtsp://192.0.2.10:8554/live");
 await dialog.getByRole("combobox",{name:"대상 Edge Node",exact:true}).selectOption("edge-a");
 await dialog.getByRole("button",{name:"장치 등록",exact:true}).click();
 await expect.poll(()=>saved).toMatchObject({name:"camera-01",protocol:"rtsp",host:"192.0.2.10",port:8554,protocolConfig:{path:"/live",timeoutMs:5000},properties:[{name:"connected",dataType:"bool",jsonPath:"connected",scale:1,offset:0}]});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

test("camera preview clears failed frames, pauses requests and restores focus on close",async({page})=>{
 const camera={id:"camera-01",uid,state:"ONLINE",targetNode:"edge-a",mapper:{ready:true},profile:{name:"camera-01",protocol:"rtsp",collectionInterval:1000,properties:[{name:"connected",dataType:"bool",jsonPath:"connected"}]},properties:{connected:{value:"true",timestamp:new Date().toISOString()}}};
 let calls=0,fail=false;
 await page.route("**/api/health",r=>r.fulfill({json:{status:"UP"}}));
 await page.route("**/api/control-plane/**",r=>{
  const url=new URL(r.request().url()),path=url.pathname.split("/api/control-plane/")[1];
  if(path==="managed-devices/camera-01/preview"){
   calls++;expect(url.searchParams.get("uid")).toBe(uid);
   return fail?r.fulfill({status:503,json:{message:"새 프레임 없음"}}):r.fulfill({contentType:"image/jpeg",body:Buffer.from("/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYwLjMxLjEwMgD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABMAAEBAAAAAAAAAAAAAAAAAAAABgEBAQAAAAAAAAAAAAAAAAAABgcQAQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCAAIAAgDASIAAhEAAxEA/9oADAMBAAIRAxEAPwCLAFF/f//Z","base64")});
  }
  if(path==="managed-devices")return r.fulfill({json:{items:[camera]}});
  return r.fulfill({json:{items:[],nodes:[],sensors:[]}});
 });
 await page.goto("/devices?view=sensors");const open=page.getByRole("button",{name:"camera-01 영상 보기",exact:true});await open.click();
 const dialog=page.getByRole("dialog",{name:"camera-01 · 영상",exact:true});const image=dialog.getByRole("img",{name:"camera-01 실시간 영상"});
 await expect(image).toBeVisible();await expect.poll(()=>image.evaluate((img:HTMLImageElement)=>img.naturalWidth)).toBe(8);
 await page.screenshot({path:test.info().outputPath("camera-preview.png")});
 fail=true;await expect(dialog.getByRole("alert")).toHaveText("새 프레임 없음");await expect(image).toHaveCount(0);
 await dialog.getByRole("button",{name:"미리보기 일시정지",exact:true}).click();const pausedCalls=calls;await page.waitForTimeout(1200);expect(calls).toBe(pausedCalls);
 fail=false;await dialog.getByRole("button",{name:"미리보기 재개",exact:true}).click();await expect(image).toBeVisible();
 await page.keyboard.press("Escape");await expect(dialog).toBeHidden();await expect(open).toBeFocused();
 const closedCalls=calls;await page.waitForTimeout(1200);expect(calls).toBe(closedCalls);
});
