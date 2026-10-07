"use client";
// Adapted from jinuk/Platform-Service/flow_project/client/src/App.jsx
import React, { useState, useRef, useCallback, useMemo, useEffect } from 'react';
import {
  ReactFlow,
  ReactFlowProvider,
  useNodesState,
  useEdgesState,
  Controls,
  Background,
  addEdge,
  MarkerType,
} from '@xyflow/react';

import '@xyflow/react/dist/style.css';
import yaml from 'js-yaml';

import K8sNode from './K8sNode';
import NodeModal from './NodeModal';

let id = 0;
const getId = () => `node_${id++}`;

const UNIFIED_IMAGE = "192.168.0.56:5000/fastdds-unified:v1.0";

// 이름 정규화 (소문자, 특수문자 -> 하이픈)
const sanitizeName = (str) => {
  if (!str) return '';
  return str.toString().toLowerCase().replace(/[^a-z0-9]/g, '-');
};

const getDeploymentName = (globalName, nodeLabel, nodeId) => {
  const cleanGlobal = sanitizeName(globalName || 'fastdds-app');
  const cleanLabel = sanitizeName(nodeLabel);
  const idNum = nodeId.split('_')[1] || '0';
  return `${cleanGlobal}-${cleanLabel}-${idNum}`;
};

const App = () => {
  const reactFlowWrapper = useRef(null);
  const trashZoneRef = useRef(null); // 휴지통 참조
  const fileInputRef = useRef(null);

  const idCounters = useRef({ pipe: 1, sensor: 1, cam: 1 });

  const getNextId = (prefix) => {
    const count = idCounters.current[prefix] || 1;
    idCounters.current[prefix] = count + 1;
    return `${prefix}${String(count).padStart(2, '0')}`;
  };

  const nodeTypes = useMemo(() => ({ k8sNode: K8sNode }), []);

  const [connection, setConnection] = useState(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [buildFirst, setBuildFirst] = useState(true);
  const [buildTag, setBuildTag] = useState("");
  const [builtImage, setBuiltImage] = useState("");
  const [appStatus, setAppStatus] = useState(null);
  const [deployedName, setDeployedName] = useState("");
  async function api(path, method = "GET", body) {
    const response = await fetch(`/api/platform-service/${path}`, { method, cache: "no-store", ...(body ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : {}) });
    const result = await response.json();
    if (!response.ok) throw new Error(typeof result.detail === "string" ? result.detail : "요청 내용을 확인하세요.");
    return result;
  }
  useEffect(() => { let stopped = false; api("config").then(value => { if (!stopped) setConnection(value); }).catch(e => { if (!stopped) setError(e.message); }); return () => { stopped = true; }; }, []);
  useEffect(() => {
    if (!deployedName) return;
    let stopped = false; let timer;
    const poll = async () => {
      try { const value = await api(`workflow/status/${deployedName}`); if (!stopped) setAppStatus(value); }
      catch (e) { if (!stopped) { setAppStatus(null); setError(e.message); } }
      if (!stopped) timer = setTimeout(poll, 5000);
    };
    void poll(); return () => { stopped = true; clearTimeout(timer); };
  }, [deployedName]);
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [reactFlowInstance, setReactFlowInstance] = useState(null);

  const [modalOpen, setModalOpen] = useState(false);
  const [selectedNode, setSelectedNode] = useState(null);
  const [isDeploying, setIsDeploying] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [isTrashActive, setIsTrashActive] = useState(false); // 휴지통 활성 상태
  const [globalAppName, setGlobalAppName] = useState('flow-project');

  // DDS Profile 상태
  const [ddsProfile, setDdsProfile] = useState('dds_discovery');

  // [수정] XML 파일 경로 (dds_discovery 모드 시 에러 방지를 위해 udp.xml 사용)
  const DDS_FILES = {
    dds_shm: '/app/dds_shm.xml',
    dds_tcp: '/app/dds_tcp.xml',
    dds_udp: '/app/dds_udp.xml',
    dds_discovery: '/app/dds_udp.xml'
  };

  // Discovery IP 입력 상태 (기본값 설정)
  const [discoveryIp, setDiscoveryIp] = useState('192.168.0.56');

  // --- 노드 추가 헬퍼 함수들 ---
  const addPipeline = (type) => {
    const baseX = 80 + nodes.length * 32;
    const baseY = 80 + nodes.length * 32;
    const newNodes = [];
    const newEdges = [];
    const pipeId = getNextId('pipe');

    if (type === 'sensor') {
      const id1 = getId(); const id2 = getId(); const id3 = getId();
      const sensorDevId = `temp${getNextId('sensor').replace('sensor','')}`;
      newNodes.push(
        { id: id1, position: { x: baseX, y: baseY }, type: 'k8sNode', data: { type: 'app', templateType: 'sensor_pub', label: 'Sensor Pub', color: '#51cf66', replicas: 1, image: UNIFIED_IMAGE, script: 'sensor_pub.py', deviceId: sensorDevId, pipelineId: pipeId, nodeSelector: {} } },
        { id: id2, position: { x: baseX + 250, y: baseY }, type: 'k8sNode', data: { type: 'app', templateType: 'alarm_service', label: 'Alarm Service', color: '#51cf66', replicas: 1, image: UNIFIED_IMAGE, script: 'alarm_service.py', deviceId: sensorDevId, pipelineId: pipeId, nodeSelector: {} } },
        { id: id3, position: { x: baseX + 500, y: baseY }, type: 'k8sNode', data: { type: 'app', templateType: 'alarm_monitor', label: 'Alarm Monitor', color: '#51cf66', replicas: 1, image: UNIFIED_IMAGE, script: 'alarm_monitor.py', deviceId: sensorDevId, pipelineId: pipeId, nodeSelector: {} } }
      );
      newEdges.push(
        { id: `e-${id1}-${id2}`, source: id1, target: id2, animated: true, style: { stroke: '#51cf66', strokeWidth: 2 }, markerEnd: { type: MarkerType.ArrowClosed, color: '#51cf66' } },
        { id: `e-${id2}-${id3}`, source: id2, target: id3, animated: true, style: { stroke: '#51cf66', strokeWidth: 2 }, markerEnd: { type: MarkerType.ArrowClosed, color: '#51cf66' } }
      );
    } else if (type === 'camera') {
      const id1 = getId(); const id2 = getId();
      const camDevId = getNextId('cam');
      newNodes.push(
        { id: id1, position: { x: baseX, y: baseY + 300 }, type: 'k8sNode', data: { type: 'app', templateType: 'camera_pub', label: 'Camera Pub', color: '#ff922b', replicas: 1, image: UNIFIED_IMAGE, script: 'camera_pub.py', rtspUrl: 'rtsp://210.99.70.120:1935/live/cctv001.stream', deviceId: camDevId, pipelineId: pipeId, nodeSelector: {} } },
        { id: id2, position: { x: baseX + 300, y: baseY + 300 }, type: 'k8sNode', data: { type: 'app', templateType: 'fps_display', label: 'FPS Display', color: '#ff922b', replicas: 1, image: UNIFIED_IMAGE, script: 'fps_display.py', deviceId: 'display', pipelineId: pipeId, nodeSelector: {} } }
      );
      newEdges.push(
        { id: `e-${id1}-${id2}`, source: id1, target: id2, animated: true, style: { stroke: '#ff922b', strokeWidth: 2 }, markerEnd: { type: MarkerType.ArrowClosed, color: '#ff922b' } }
      );
    }
    setNodes((nds) => [...nds, ...newNodes]);
    setEdges((eds) => [...eds, ...newEdges]);
  };

  const addSingleNode = (templateType) => {
    const position = { x: 80 + nodes.length * 48, y: 80 + nodes.length * 48 };
    setNodes((nds) => nds.concat({ id: getId(), type: 'k8sNode', position, data: createInitialData(templateType) }));
  };

  const createInitialData = (typeString) => {
    const defaultPipe = 'pipe01';
    let initialData = { label: 'Node', nodeSelector: {} };
    if (typeString === 'discovery') initialData = { type: 'discovery', templateType: 'discovery', label: 'Discovery Server', color: '#339af0', replicas: 1, image: 'eprosima/vulcanexus:humble', command: '/bin/bash, -c, source /opt/vulcanexus/humble/setup.bash && fastdds discovery -i 0 -p 11811', port: 11811, nodeSelector: {} };
    else if (typeString === 'sensor_pub') initialData = { type: 'app', templateType: 'sensor_pub', label: 'Sensor Pub', color: '#51cf66', replicas: 1, image: UNIFIED_IMAGE, script: 'sensor_pub.py', deviceId: `temp${getNextId('sensor').replace('sensor','')}`, pipelineId: defaultPipe, nodeSelector: {} };
    else if (typeString === 'alarm_service') initialData = { type: 'app', templateType: 'alarm_service', label: 'Alarm Service', color: '#51cf66', replicas: 1, image: UNIFIED_IMAGE, script: 'alarm_service.py', deviceId: 'temp01', pipelineId: defaultPipe, nodeSelector: {} };
    else if (typeString === 'alarm_monitor') initialData = { type: 'app', templateType: 'alarm_monitor', label: 'Alarm Monitor', color: '#51cf66', replicas: 1, image: UNIFIED_IMAGE, script: 'alarm_monitor.py', deviceId: 'temp01', pipelineId: defaultPipe, nodeSelector: {} };
    else if (typeString === 'camera_pub') initialData = { type: 'app', templateType: 'camera_pub', label: 'Camera Pub', color: '#ff922b', replicas: 1, image: UNIFIED_IMAGE, script: 'camera_pub.py', rtspUrl: 'rtsp://210.99.70.120:1935/live/cctv001.stream', deviceId: getNextId('cam'), pipelineId: defaultPipe, nodeSelector: {} };
    else if (typeString === 'fps_display') initialData = { type: 'app', templateType: 'fps_display', label: 'FPS Display', color: '#ff922b', replicas: 1, image: UNIFIED_IMAGE, script: 'fps_display.py', deviceId: 'display', pipelineId: defaultPipe, nodeSelector: {} };
    return initialData;
  };

  // --- 드래그 앤 드롭 & 휴지통 로직 ---
  const onDragStart = (event, typeString) => { event.dataTransfer.setData('application/reactflow', typeString); event.dataTransfer.effectAllowed = 'move'; };
  const onDragOver = useCallback((event) => { event.preventDefault(); event.dataTransfer.dropEffect = 'move'; }, []);
  const onDrop = (event) => {
      event.preventDefault();
      const typeString = event.dataTransfer.getData('application/reactflow');
      if (!typeString || !reactFlowInstance) return;
      const position = reactFlowInstance.screenToFlowPosition({ x: event.clientX, y: event.clientY });
      setNodes((nds) => nds.concat({ id: getId(), type: 'k8sNode', position, data: createInitialData(typeString) }));
    };

  const onNodeDrag = useCallback((event) => {
    if (trashZoneRef.current) {
      const rect = trashZoneRef.current.getBoundingClientRect();
      const isOver = event.clientX >= rect.left && event.clientX <= rect.right && event.clientY >= rect.top && event.clientY <= rect.bottom;
      setIsTrashActive(isOver);
    }
  }, []);

  const onNodeDragStop = useCallback((event, node) => {
    if (trashZoneRef.current) {
      const rect = trashZoneRef.current.getBoundingClientRect();
      if (event.clientX >= rect.left && event.clientX <= rect.right && event.clientY >= rect.top && event.clientY <= rect.bottom) {
        setNodes((nds) => nds.filter((n) => n.id !== node.id));
        setEdges((eds) => eds.filter(e => e.source !== node.id && e.target !== node.id));
      }
      setIsTrashActive(false);
    }
  }, [setNodes, setEdges]);

  const handleFileSelect = (event) => {
    const file = event.target.files[0]; if (!file) return; if (file.size > 1000000) { setError('YAML 파일은 1MB 이하여야 합니다.'); return; }
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const content = e.target.result; const docs = yaml.loadAll(content); const newNodes = []; let yOffset = 0; const saved = docs.find(d => d?.metadata?.annotations?.['ui.canvas/app-name'])?.metadata.annotations;
        docs.forEach((doc) => {
          if (!doc || doc.kind !== 'Deployment') return;
          const meta = doc.metadata || {}; const spec = doc.spec || {}; const container = spec.template?.spec?.containers?.[0] || {};
          const args = container.args || []; const importedSelector = spec.template?.spec?.nodeSelector || {};
          let position = { x: 100, y: 100 + yOffset };
          if (meta.annotations?.['ui.canvas/x']) position = { x: Number(meta.annotations['ui.canvas/x']), y: Number(meta.annotations['ui.canvas/y']) }; else yOffset += 250;

          if (meta.name?.includes('discovery')) {
             newNodes.push({ id: meta.annotations?.['ui.canvas/id'] || getId(), type: 'k8sNode', position, data: { type: 'discovery', templateType: 'discovery', label: 'Discovery', color: '#339af0', appName: meta.name, replicas: spec.replicas ?? 1, image: container.image, command: container.command?.join(' '), nodeSelector: importedSelector } });
             return;
          }
          let templateType = 'app'; let label = 'App'; let color = '#868e96'; let script = args.find(arg => typeof arg === 'string' && arg.endsWith('.py')) || '';
          let devId = 'dev1';
          if (args.includes('--camera_id')) devId = args[args.indexOf('--camera_id')+1];
          if (args.includes('--sensor_id')) devId = args[args.indexOf('--sensor_id')+1];

          if (script.includes('sensor')) { templateType = 'sensor_pub'; label = 'Sensor Pub'; color = '#51cf66'; }
          else if (script.includes('alarm') && script.includes('service')) { templateType = 'alarm_service'; label = 'Alarm Service'; color = '#51cf66'; }
          else if (script.includes('alarm') && script.includes('monitor')) { templateType = 'alarm_monitor'; label = 'Alarm Monitor'; color = '#51cf66'; }
          else if (script.includes('camera')) { templateType = 'camera_pub'; label = 'Camera Pub'; color = '#ff922b'; }
          else if (script.includes('fps')) { templateType = 'fps_display'; label = 'FPS Display'; color = '#ff922b'; }

          newNodes.push({ id: meta.annotations?.['ui.canvas/id'] || getId(), type: 'k8sNode', position, data: { type: 'app', templateType, label, color, appName: meta.name, replicas: spec.replicas ?? 1, image: container.image, script, deviceId: devId, pipelineId: meta.annotations?.['ui.canvas/pipeline'] || 'pipe01', rtspUrl: args.includes('--rtsp') ? args[args.indexOf('--rtsp')+1] : '', nodeSelector: importedSelector } });
        });
        if (newNodes.length > 0) {
          if (new Set(newNodes.map(n => n.id)).size !== newNodes.length) throw new Error('중복된 노드 ID입니다.');
          if (newNodes.some(n => !Number.isFinite(n.position.x) || !Number.isFinite(n.position.y) || !n.data.image)) throw new Error('노드 좌표와 이미지를 확인하세요.');
          if (nodes.length && !window.confirm('현재 편집 내용을 YAML 파일로 교체할까요?')) return;
          const savedEdges = JSON.parse(saved?.['ui.canvas/edges'] || '[]');
          if (!Array.isArray(savedEdges)) throw new Error('잘못된 연결 정보입니다.');
          for (const node of newNodes) {
            const number = /^node_(\d+)$/.exec(node.id);
            if (number) id = Math.max(id, Number(number[1]) + 1);
            for (const [counter, value, pattern] of [['pipe', node.data.pipelineId, /^pipe(\d+)$/], ['sensor', node.data.deviceId, /^(?:temp|sensor)(\d+)$/], ['cam', node.data.deviceId, /^cam(\d+)$/]]) {
              const match = pattern.exec(value || '');
              if (match) idCounters.current[counter] = Math.max(idCounters.current[counter], Number(match[1]) + 1);
            }
          }
          setNodes(newNodes); setEdges(savedEdges.filter(edge => edge && newNodes.some(n=>n.id===edge.source) && newNodes.some(n=>n.id===edge.target)).map(edge => ({ ...edge, animated: true, style: {stroke:'#228be6',strokeWidth:2}, markerEnd: {type:MarkerType.ArrowClosed,color:'#228be6'} })));
          if (saved) { setGlobalAppName(saved['ui.canvas/app-name']); setDdsProfile(saved['ui.canvas/dds-profile'] || 'dds_discovery'); setDiscoveryIp(saved['ui.canvas/discovery-ip'] || ''); }
          setBuiltImage(''); setNotice('YAML을 불러왔습니다.');
        }
      } catch (err) { alert(`오류: ${err.message}`); }
      if (fileInputRef.current) fileInputRef.current.value = '';
    };
    reader.readAsText(file);
  };

  // --- YAML Export (핵심 로직) ---
  const usesBuildImage = image => {
    const repositories = [UNIFIED_IMAGE.slice(0, UNIFIED_IMAGE.lastIndexOf(':')), connection?.imageRepository].filter(Boolean);
    return typeof image === 'string' && repositories.some(repo => image === repo || image.startsWith(repo + ':') || image.startsWith(repo + '@'));
  };
  const generateYamlString = (imageOverride = builtImage) => {
    const k8sManifests = [];
    const ddsConfigPath = DDS_FILES[ddsProfile] || '/app/dds_udp.xml';

    const appPrefix = sanitizeName(globalAppName) || 'app';
    const edgeToleration = [{ key: "environment", operator: "Equal", value: "edge", effect: "NoSchedule" }];
    const discoveryNode = nodes.find(n => n.data.type === 'discovery');

    // [수정] 서비스 이름도 App Prefix 사용
    const discoveryServiceName = `${appPrefix}-discovery`;
    const discoveryPort = 11811;

    nodes.forEach((node) => {
      const d = node.data;
      const uiAnnotations = {
        'ui.canvas/id': node.id,
        'ui.canvas/pipeline': d.pipelineId || 'pipe01',
        'ui.canvas/x': String(Math.round(node.position.x)),
        'ui.canvas/y': String(Math.round(node.position.y)),
        'ui.canvas/template': d.templateType,
      };

      const nodeSelectorConfig = (d.nodeSelector && Object.keys(d.nodeSelector).length > 0) ? d.nodeSelector : undefined;
      const pipeId = sanitizeName(d.pipelineId || 'pipe01');
      const devId = sanitizeName(d.deviceId || 'dev01');

      if (d.type === 'discovery') {
        const uniqueName = sanitizeName(d.appName || getDeploymentName(globalAppName, d.label, node.id));

        k8sManifests.push({
          apiVersion: 'v1', kind: 'Service',
          metadata: { name: discoveryServiceName },
          spec: {
            selector: { app: discoveryServiceName },
            ports: [
              { name: 'dds-udp', protocol: 'UDP', port: discoveryPort, targetPort: discoveryPort },
              { name: 'dds-tcp', protocol: 'TCP', port: discoveryPort, targetPort: discoveryPort }
            ]
          }
        });

        k8sManifests.push({
          apiVersion: 'apps/v1', kind: 'Deployment',
          metadata: { name: uniqueName, annotations: uiAnnotations },
          spec: {
            replicas: Number(d.replicas),
            selector: { matchLabels: { app: discoveryServiceName } },
            template: {
              metadata: { labels: { app: discoveryServiceName } },
              spec: {
                nodeSelector: nodeSelectorConfig,
                tolerations: edgeToleration,
                hostNetwork: true,
                hostIPC: true,
                containers: [{
                  name: 'server',
                  image: d.image,
                  imagePullPolicy: 'Always',
                  command: ["/bin/bash", "-c", `source /opt/vulcanexus/humble/setup.bash && fastdds discovery -i 0 -p ${discoveryPort}`]
                }]
              }
            }
          }
        });
      }
      else if (d.type === 'app') {
        let rawName = d.appName;
        if (!rawName) {
           // [수정] App Name Prefix 적용
           if (d.templateType === 'sensor_pub') rawName = `${appPrefix}-sensor-${devId}`;
           else if (d.templateType === 'alarm_service') rawName = `${appPrefix}-alarm-svc-${devId}`;
           else if (d.templateType === 'alarm_monitor') rawName = `${appPrefix}-alarm-mon-${devId}`;
           else if (d.templateType === 'camera_pub') rawName = `${appPrefix}-camera-${devId}`;
           else if (d.templateType === 'fps_display') rawName = `${appPrefix}-fps-${pipeId}`;
           else rawName = getDeploymentName(globalAppName, d.label, node.id);
        }

        let k8sArgs = [];
        if (d.templateType === 'sensor_pub') k8sArgs = ["python3", "-u", "sensor_pub.py", "--sensor_id", devId];
        else if (d.templateType === 'alarm_service') k8sArgs = ["python3", "-u", "alarm_service.py"];
        else if (d.templateType === 'alarm_monitor') k8sArgs = ["python3", "-u", "alarm_monitor.py"];
        else if (d.templateType === 'camera_pub') k8sArgs = ["python3", "-u", "camera_pub.py", "--topic", `${pipeId}_video_shared`, "--rtsp", d.rtspUrl || "", "--camera_id", devId];
        else if (d.templateType === 'fps_display') k8sArgs = ["python3", "-u", "fps_display.py", "--topic", `${pipeId}_video_shared`];
        else k8sArgs = ["python3", "-u", d.script];

        const k8sName = sanitizeName(rawName);
        const k8sLabel = sanitizeName(rawName);

        const envVars = [{ name: 'FASTRTPS_DEFAULT_PROFILES_FILE', value: ddsConfigPath }];
        if (discoveryNode) {
          // [수정] IP 입력값의 콤마(,)를 점(.)으로 자동 보정
          const inputIp = discoveryIp && discoveryIp.trim() !== '' ? discoveryIp : discoveryServiceName;
          const cleanIp = inputIp.replace(/,/g, '.');

          envVars.push({ name: 'ROS_DISCOVERY_SERVER', value: `${cleanIp}:${discoveryPort}` });
        }

        k8sManifests.push({
          apiVersion: 'apps/v1', kind: 'Deployment',
          metadata: { name: k8sName, annotations: uiAnnotations },
          spec: {
            replicas: Number(d.replicas),
            selector: { matchLabels: { app: k8sLabel } },
            template: {
              metadata: { labels: { app: k8sLabel } },
              spec: {
                hostNetwork: true, hostIPC: true, dnsPolicy: 'ClusterFirstWithHostNet',
                nodeSelector: nodeSelectorConfig, tolerations: edgeToleration,
                containers: [{
                    name: 'app', image: imageOverride && usesBuildImage(d.image) ? imageOverride : d.image, imagePullPolicy: 'Always',
                    args: k8sArgs,
                    env: envVars
                }]
              }
            }
          }
        });
      }
    });
    const identities = new Set();
    for (const manifest of k8sManifests) {
      const name = manifest.metadata.name;
      if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(name) || !name.startsWith(appPrefix+'-')) throw new Error('Deployment Name은 App Name 접두사로 시작하는 63자 이내 이름이어야 합니다.');
      const identity = manifest.kind + '/' + name;
      if (identities.has(identity)) throw new Error('Deployment 이름이 중복됩니다. 노드 설정의 Deployment Name을 구분하세요.');
      identities.add(identity);
      if (manifest.kind === 'Deployment' && (!Number.isInteger(manifest.spec.replicas) || manifest.spec.replicas < 0 || manifest.spec.replicas > 50)) throw new Error('Replicas는 0~50의 정수여야 합니다.');
    }
    if (k8sManifests.length) k8sManifests[0].metadata.annotations = { ...k8sManifests[0].metadata.annotations,
      'ui.canvas/app-name': appPrefix, 'ui.canvas/dds-profile': ddsProfile, 'ui.canvas/discovery-ip': discoveryIp,
      'ui.canvas/edges': JSON.stringify(edges.map(({id,source,target})=>({id,source,target}))) };
    return k8sManifests.map(doc => yaml.dump(doc, { lineWidth: -1, noRefs: true })).join('\n---\n');
  };

  const onConnect = useCallback((params) => setEdges((eds) => addEdge({ ...params, animated: true, style: { stroke: '#228be6', strokeWidth: 2 }, markerEnd: { type: MarkerType.ArrowClosed, color: '#228be6' } }, eds)), [setEdges]);
  const onNodeDoubleClick = (event, node) => { if (isDeploying || isDeleting) return; setSelectedNode(node); setModalOpen(true); };
  const onSaveNodeData = (nodeId, newData) => { setNodes((nds) => nds.map((n) => (n.id === nodeId ? { ...n, data: newData } : n))); };

  const handleBuild = async () => {
    const tag = buildTag || `workflow-${Date.now()}`;
    setNotice("Buildx 이미지 빌드·푸시 중…");
    const result = await api("workflow/build-image", "POST", { tag });
    setBuiltImage(result.image); setBuildTag(tag);
    return result.image;
  };
  const handleDeploy = async () => {
    if (!nodes.length) { setError("배포할 노드가 없습니다."); return; }
    const deployName = sanitizeName(globalAppName);
    if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(deployName)) { setError("앱 이름은 영문 소문자·숫자·하이픈 63자 이내로 입력하세요."); return; }
    setIsDeploying(true); setError(""); setAppStatus(null);
    try {
      // Validate the draft before incurring a build or touching Git.
      generateYamlString();
      const image = buildFirst && nodes.some(n => n.data.type === 'app' && usesBuildImage(n.data.image)) ? await handleBuild() : builtImage;
      const yamlString = generateYamlString(image);
      setNotice("YAML을 Gitea에 저장하고 Argo CD 앱을 생성·갱신하는 중…");
      const result = await api("workflow/save-and-push", "POST", { content: yamlString, local_directory:`./workspace/${deployName}`, filename:"deployment.yaml", overwrite_local:true, repo_path:`${deployName}/deployment.yaml`, commit_message:`Deploy ${deployName}` });
      setDeployedName(deployName);
      if (result.argocd_result.status === "failed") throw new Error(`Gitea 저장 완료 · Argo CD 단계 실패: ${result.argocd_result.error}`);
      setAppStatus(result.argocd_result); setNotice("Gitea 저장과 Argo CD 갱신을 요청했습니다. 실제 동기화·준비 상태는 아래에서 확인하세요.");
    } catch (e) { setError(e.message); setNotice(""); }
    finally { setIsDeploying(false); }
  };
  const handleDelete = async () => {
    const name = sanitizeName(globalAppName);
    if (!name || !window.confirm(`${name}의 Argo CD 앱과 배포 리소스, Gitea YAML을 삭제할까요?`)) return;
    setIsDeleting(true); setError("");
    try {
      const result = await api("workflow/delete", "DELETE", {local_directory:`./workspace/${name}`,filename:"deployment.yaml",repo_path:`${name}/deployment.yaml`,commit_message:`Delete ${name}`});
      setDeployedName(name); setAppStatus(null); setNotice(result.argocd.status === "absent" ? "배포 앱이 없으며 Git 정의를 정리했습니다." : "Argo CD 앱 삭제를 요청하고 Git 정의를 삭제했습니다. 리소스 종료를 확인 중입니다.");
    } catch (e) { setError(e.message); }
    finally { setIsDeleting(false); }
  };

  const exportToYaml = () => { try { const s = generateYamlString(); if (!s) throw new Error('추가된 노드가 없습니다.'); const l = document.createElement('a'); const url = URL.createObjectURL(new Blob([s], {type:'text/yaml'})); l.href = url; l.download = 'fastdds-deployment.yaml'; l.click(); setTimeout(()=>URL.revokeObjectURL(url),1000); } catch(e) {setError(e.message);} };

  const sideItemStyle = (color) => ({ display:'flex', alignItems:'center', justifyContent:'space-between', padding: '10px', marginBottom: '8px', borderRadius: '4px', background: 'white', border: `1px solid ${color}`, borderLeft: `5px solid ${color}`, fontSize: '13px', fontWeight: 'bold', boxShadow: '0 1px 3px rgba(0,0,0,0.05)' });
  const plusBtnStyle = { marginLeft:'10px', width:'24px', height:'24px', borderRadius:'50%', border:'none', background:'#eee', color:'#555', cursor:'pointer', display:'flex', alignItems:'center', justifyContent:'center', fontSize:'16px' };

  return (
    <><div className="platform-service-status" aria-live="polite">
      <p>{connection?.configured ? `Gitea ${connection.repository} · ${connection.branch}` : "배포 API 연결 확인 필요"}</p>
      <button onClick={()=>{setError("");api("config").then(setConnection).catch(e=>setError(e.message));}}>연결 다시 확인</button>
      {connection?.missing?.length > 0 && <p className="error">서버 설정 필요: {connection.missing.join(", ")}</p>}
      {notice && <p role="status">{notice}</p>}{error && <p className="error" role="alert">{error}</p>}
      {appStatus && <p>Argo CD · {appStatus.name} · {appStatus.status === "absent" ? "앱 없음" : `${appStatus.deleting ? "삭제 진행 중 · " : ""}동기화 ${appStatus.sync} · 준비 ${appStatus.health}`}</p>}
      <p className="hint">원본 DDS 템플릿입니다. Sensor Pub은 시험 데이터를 발행하며 연결선은 편집용입니다. 실제 통신은 DDS 토픽 설정을 따릅니다.</p>
    </div><div className="platform-service-editor">
      <aside className="platform-service-library"><fieldset disabled={isDeploying || isDeleting} className="platform-service-fields">
        <div style={{ marginBottom: '20px', borderBottom: '1px solid #eee', paddingBottom: '15px' }}>
          <label style={{ display:'block', fontSize:'13px', fontWeight:'bold', color:'#333', marginBottom:'5px' }}>App Name (Global Prefix)</label>
          <input aria-label="App Name" type="text" placeholder="e.g. flow-project" value={globalAppName} onChange={(e) => setGlobalAppName(e.target.value)} style={{ width:'100%', padding:'8px', border:'1px solid #ccc', borderRadius:'4px', marginBottom:'15px', boxSizing:'border-box' }} />
          <label style={{ display:'block', fontSize:'13px', fontWeight:'bold', color:'#333', marginBottom:'5px' }}>DDS Profile (Global)</label>
          <select aria-label="DDS Profile" value={ddsProfile} onChange={(e) => setDdsProfile(e.target.value)} style={{ width:'100%', padding:'8px', border:'1px solid #ccc', borderRadius:'4px', backgroundColor:'#f8f9fa', cursor:'pointer', marginBottom:'15px' }}>
            <option value="dds_shm">dds_shm (Shared Memory)</option>
            <option value="dds_tcp">dds_tcp (TCP/IP)</option>
            <option value="dds_udp">dds_udp (UDP/IP)</option>
            <option value="dds_discovery">dds_discovery (Super Client)</option>
          </select>

          <label style={{ display:'block', fontSize:'13px', fontWeight:'bold', color:'#d6336c', marginBottom:'5px' }}>Discovery Server IP (Edge)</label>
          <input
            type="text"
            aria-label="Discovery Server IP" placeholder="e.g. 192.168.0.188"
            value={discoveryIp}
            onChange={(e) => setDiscoveryIp(e.target.value)}
            style={{ width:'100%', padding:'8px', border:'1px solid #d6336c', borderRadius:'4px', backgroundColor:'#fff0f6' }}
            title="KubeEdge 환경에서는 Service DNS 대신 실제 Cloud Node IP를 입력하세요."
          />
        </div>

        <h3 style={{ margin: '0 0 10px 0', fontSize: '14px', color:'#555' }}>Infrastructure</h3>
        <div draggable onDragStart={(e) => onDragStart(e, 'discovery')} style={sideItemStyle('#339af0')}><span>Discovery Server</span></div>
        <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', margin: '15px 0 10px 0'}}><h3 style={{ margin: 0, fontSize: '14px', color:'#555' }}>Sensor Nodes</h3><button onClick={() => addPipeline('sensor')} style={{fontSize:'11px', padding:'3px 6px', background:'#51cf66', color:'white', border:'none', borderRadius:'4px', cursor:'pointer'}}>+ Add Pipeline</button></div>
        <div draggable onDragStart={(e) => onDragStart(e, 'sensor_pub')} style={sideItemStyle('#51cf66')}><span>Sensor Pub</span><button style={plusBtnStyle} onClick={() => addSingleNode('sensor_pub')}>+</button></div>
        <div draggable onDragStart={(e) => onDragStart(e, 'alarm_service')} style={sideItemStyle('#51cf66')}><span>Alarm Service</span><button style={plusBtnStyle} onClick={() => addSingleNode('alarm_service')}>+</button></div>
        <div draggable onDragStart={(e) => onDragStart(e, 'alarm_monitor')} style={sideItemStyle('#51cf66')}><span>Alarm Monitor</span><button style={plusBtnStyle} onClick={() => addSingleNode('alarm_monitor')}>+</button></div>
        <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', margin: '15px 0 10px 0'}}><h3 style={{ margin: 0, fontSize: '14px', color:'#555' }}>Camera Nodes</h3><button onClick={() => addPipeline('camera')} style={{fontSize:'11px', padding:'3px 6px', background:'#ff922b', color:'white', border:'none', borderRadius:'4px', cursor:'pointer'}}>+ Add Pipeline</button></div>
        <div draggable onDragStart={(e) => onDragStart(e, 'camera_pub')} style={sideItemStyle('#ff922b')}><span>Camera Pub</span><button style={plusBtnStyle} onClick={() => addSingleNode('camera_pub')}>+</button></div>
        <div draggable onDragStart={(e) => onDragStart(e, 'fps_display')} style={sideItemStyle('#ff922b')}><span>FPS Display</span><button style={plusBtnStyle} onClick={() => addSingleNode('fps_display')}>+</button></div>
        <div className="platform-build"><label><input type="checkbox" checked={buildFirst} onChange={e=>setBuildFirst(e.target.checked)} /> 배포 전 Buildx 빌드·푸시</label><label>이미지 태그<input value={buildTag} onChange={e=>{setBuildTag(e.target.value);setBuiltImage("");}} placeholder="미입력 시 자동 생성" /></label><small>플랫폼: {connection?.platforms?.join(", ") || "서버 설정"}</small>
        <button disabled={isDeploying || isDeleting || !connection?.buildConfigured} onClick={async ()=>{setIsDeploying(true);setError("");try {await handleBuild();setNotice("이미지 빌드·푸시 완료");}catch(e){setError(e.message);setNotice("");}finally{setIsDeploying(false);}}}>이미지 빌드·푸시</button>{builtImage && <p className="digest">{builtImage}</p>}</div>
        <div style={{marginTop: 'auto', paddingTop: '20px', display: 'flex', flexDirection: 'column', gap: '10px'}}><input type="file" aria-label="워크플로 YAML" ref={fileInputRef} onChange={handleFileSelect} style={{ display: 'none' }} /><button onClick={() => fileInputRef.current.click()} style={btnStyle('#495057')}>Import YAML</button><button onClick={exportToYaml} style={btnStyle('#228be6')}>Download YAML</button><button onClick={handleDeploy} disabled={isDeploying || isDeleting || !connection?.configured} style={btnStyle(isDeploying ? '#ccc' : '#12b886')}>{isDeploying ? 'Deploying...' : 'Deploy to GitOps'}</button><button onClick={handleDelete} disabled={isDeploying || isDeleting || !connection?.configured} style={btnStyle(isDeleting ? '#ccc' : '#fa5252')}>{isDeleting ? 'Deleting...' : 'Delete App'}</button></div>
      </fieldset></aside>
      <div className="platform-service-canvas" ref={reactFlowWrapper}>
        <ReactFlow nodes={nodes} edges={edges} onNodesChange={onNodesChange} onNodesDelete={deleted => setEdges(es => es.filter(e => !deleted.some(n => n.id === e.source || n.id === e.target)))} onEdgesChange={onEdgesChange} onConnect={onConnect} onNodeDoubleClick={onNodeDoubleClick} onInit={setReactFlowInstance} minZoom={0.1} onDrop={onDrop} onDragOver={onDragOver} onNodeDrag={onNodeDrag} onNodeDragStop={onNodeDragStop} nodeTypes={nodeTypes} nodesDraggable={!isDeploying && !isDeleting} nodesConnectable={!isDeploying && !isDeleting} fitView style={{ background: '#f8f9fa' }} deleteKeyCode={['Backspace', 'Delete']}><Controls /><Background color="#ccc" gap={20} variant="dots" /></ReactFlow>
        <div id="trash-zone" ref={trashZoneRef} style={{ position: 'absolute', bottom: '20px', right: '20px', width: '70px', height: '70px', borderRadius: '50%', backgroundColor: isTrashActive ? '#ff6b6b' : '#fff', border: '2px solid #ff6b6b', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '14px', fontWeight: 'bold', color: isTrashActive ? '#fff' : '#ff6b6b', boxShadow: '0 4px 6px rgba(0,0,0,0.1)', zIndex: 10, transition: 'all 0.2s ease', cursor: 'default', transform: isTrashActive ? 'scale(1.2)' : 'scale(1)' }}>Trash</div>
      </div>
      {modalOpen && selectedNode && <NodeModal key={selectedNode.id} node={selectedNode} onClose={() => setModalOpen(false)} onSave={onSaveNodeData} />}
    </div></>
  );
};

const btnStyle = (bg) => ({ width: '100%', padding: '12px', background: bg, color: 'white', border: 'none', borderRadius: '6px', cursor: 'pointer', fontWeight: 'bold', transition: 'background 0.2s', marginTop: '5px' });

export default function PlatformService() { return ( <ReactFlowProvider> <App /> </ReactFlowProvider> ); }
