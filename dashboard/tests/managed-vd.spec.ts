import { test, expect } from "@playwright/test";

const id="11111111-1111-4111-8111-111111111111", service="22222222-2222-4222-8222-222222222222";
const template={id,kind:"VD",key:"sensor-template",version:"1.0.0",spec:{apiVersion:"edgeai.vd/v2",type:"sensorMirror",serviceProfileVersionId:service,
  sources:{input:{deviceModel:"edgeai-model-temperature",required:true}},state:{mode:"STATELESS"},runtime:{maxConcurrentTasks:1,startupTimeoutSeconds:60,drainTimeoutSeconds:60}}};
const sensor={id:"sensehat-001",uid:"33333333-3333-4333-8333-333333333333",model:"edgeai-model-temperature",state:"ONLINE",targetNode:"edge-node",profile:{kind:"physical",properties:[{name:"temperature",dataType:"float64",unit:"°C"},{name:"humidity",dataType:"float64",unit:"%"}]},properties:{temperature:{value:"26.5",timestamp:Date.now(),unit:"°C"},humidity:{value:"",timestamp:0}},mapper:{ready:true}};
test("VD uses sensor inventory and does not offer duplicate app device registration",async({page},testInfo)=>{
  let created=false, posts=0;
  const requested:string[]=[];
  const vd={id,key:"sensor-vd",displayName:"온도 VD",profileVersionId:id,serviceProfileVersionId:service,state:"REGISTERED",revision:0,placement:{mode:"AUTO"}};
  await page.route("**/api/control-plane/**",async route=>{
    const path=new URL(route.request().url()).pathname.replace("/api/control-plane/","");requested.push(path);
    if(path==="csrf")return route.fulfill({json:{token:"test"}});
    if(path==="profiles/VD")return route.fulfill({json:{items:[template],nextOffset:null}});
    if(path==="profiles/SERVICE")return route.fulfill({json:{items:[{id:service,kind:"SERVICE",key:"processor",version:"1.0.0"}],nextOffset:null}});
    if(path==="nodes")return route.fulfill({json:{items:[],nextOffset:null}});
    if(path==="managed-devices")return route.fulfill({json:{items:[sensor]}});
    if(path==="virtual-devices"&&route.request().method()==="POST"){
      expect(route.request().postDataJSON()).toEqual({key:"sensor-vd",displayName:"온도 VD",profileVersionId:id,sources:[{sourceKey:"input",deviceId:"sensehat-001",properties:["temperature"],maxAgeSeconds:45}],placement:{mode:"AUTO"}});
      if(++posts===1)return route.fulfill({status:503,json:{message:"원본 조회에 실패했습니다. 다시 저장하세요."}});
      created=true;return route.fulfill({status:201,json:vd});
    }
    if(path==="virtual-devices")return route.fulfill({json:{items:created?[vd]:[],nextOffset:null}});
    if(path===`virtual-devices/${id}`)return route.fulfill({json:{vd,activeSources:[{id,sourceKey:"input",deviceId:sensor.id,deviceUid:sensor.uid,deviceModel:sensor.model,sourceKind:"physical",properties:["temperature"],maxAgeSeconds:45}],sourceHistory:[],sourceHistoryTruncated:false}});
    if(path===`virtual-devices/${id}/execution`)return route.fulfill({json:{vdId:id,enabled:false,current:null,pendingOperation:null,runtimeHistory:[],bindings:[],operations:[]}});
    return route.fulfill({status:404,json:{message:"Unknown fixture"}});
  });
  await page.goto("/virtual-devices?profile="+id);
  await expect(page.getByLabel("input · 필수 원본")).toBeVisible();
  await page.getByLabel("VD 키",{exact:true}).fill("sensor-vd");
  await page.getByLabel("VD 이름",{exact:true}).fill("온도 VD");
  await page.getByLabel("input · 필수 원본").selectOption(sensor.id);
  await expect(page.getByText(/1970/)).toHaveCount(0);
  await expect(page.getByText("미수집",{exact:true})).toBeVisible();
  await page.getByLabel("input · temperature 선택").check();
  await page.getByLabel("input · 최대 수신 경과 시간(초)").fill("45");
  await expect(page.getByText("원본 장치 바로 등록",{exact:true})).toHaveCount(0);
  await expect(page.getByText("장치 규격 DEVICE 등록",{exact:true})).toHaveCount(0);
  await page.getByRole("button",{name:"VD 등록",exact:true}).click();
  await expect(page.getByRole("alert").filter({hasText:"원본 조회에 실패"})).toBeVisible();
  await expect(page.getByLabel("input · temperature 선택")).toBeChecked();
  await expect(page.getByLabel("input · 최대 수신 경과 시간(초)")).toHaveValue("45");
  await page.getByRole("button",{name:"VD 등록",exact:true}).click();
  await expect(page.getByRole("heading",{name:"온도 VD",exact:true})).toBeVisible();
  await expect(page.getByRole("status").filter({hasText:"가상 장치와 원본 연결"})).toBeVisible();
  await expect(page.getByRole("form",{name:"VD 설정 수정"}).getByLabel("input · temperature 선택")).toBeChecked();
  await expect(page.getByRole("form",{name:"VD 설정 수정"}).getByLabel("input · 최대 수신 경과 시간(초)")).toHaveValue("45");
  expect(requested).toContain("managed-devices");expect(requested).not.toContain("devices");
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:testInfo.outputPath("managed-vd.png"),fullPage:true});
});
test("Devices removes registered device tab and routes old links to sensor inventory",async({page})=>{
  const requested:string[]=[];
  await page.route("**/api/control-plane/**",route=>{
    const path=new URL(route.request().url()).pathname.replace("/api/control-plane/","");requested.push(path);
    if(path==="nodes")return route.fulfill({json:{items:[],nextOffset:null}});
    if(path==="managed-devices")return route.fulfill({json:{items:[]}});
    if(path==="device-profiles")return route.fulfill({json:{items:[]}});
    return route.fulfill({status:503,json:{message:"unavailable"}});
  });
  await page.goto("/devices?view=devices");
  await expect(page.getByRole("button",{name:"센서",exact:true})).toHaveAttribute("aria-pressed","true");
  await expect(page.getByRole("button",{name:"등록 장치",exact:true})).toHaveCount(0);
  expect(requested).not.toContain("devices");
});
