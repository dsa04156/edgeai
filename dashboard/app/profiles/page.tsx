import Link from "next/link";
import { ProfileRegistry } from "./profile-registry";

export default function Profiles() {
  return <main>
    <header><Link className="brand" href="/">EdgeAI</Link><span className="stage">M1 · Profile</span></header>
    <section>
      <p className="eyebrow">PROFILE REGISTRY</p>
      <h1>실행의 기준을 버전으로 남깁니다.</h1>
      <p className="intro">장치·서비스·가상 디바이스의 규격을 등록하고 조회합니다. 발행된 내용은 보존되며, 변경할 때는 새 버전을 등록합니다.</p>
    </section>
    <ProfileRegistry />
    <footer>등록된 규격의 실행 호환성과 실장비 동작은 아직 검증하지 않습니다.</footer>
  </main>;
}
