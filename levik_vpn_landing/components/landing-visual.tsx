import Image from "next/image";
import {
  CheckIcon,
  ShieldCheckIcon,
  SignalIcon,
  SupportIcon,
} from "@/components/icons";

export function LandingVisual() {
  return (
    <div
      aria-label="Levik VPN подключён на мобильном устройстве"
      className="hero-visual"
      role="img"
    >
      <div aria-hidden="true" className="hero-visual__orbit hero-visual__orbit--one" />
      <div aria-hidden="true" className="hero-visual__orbit hero-visual__orbit--two" />
      <div aria-hidden="true" className="hero-visual__beam hero-visual__beam--one" />
      <div aria-hidden="true" className="hero-visual__beam hero-visual__beam--two" />

      <div className="device-scene">
        <div className="device-scene__logo">
          <Image alt="" height={132} src="/assets/levik-logo.webp" width={132} />
        </div>

        <div aria-hidden="true" className="device-scene__laptop">
          <div className="device-scene__laptop-screen">
            <span>LTE subscription</span>
          </div>
        </div>

        <div aria-hidden="true" className="device-scene__tablet">
          <span className="device-scene__eyebrow">Проверка сети</span>
          <strong className="device-scene__bad">Обычный VPN</strong>
          <small>нет подключения</small>
          <strong className="device-scene__good">Levik LTE</strong>
          <small>статус: стабильно</small>
        </div>

        <div className="device-scene__phone">
          <div className="device-scene__phone-top">
            <span>LTE</span>
            <span>92%</span>
          </div>
          <div className="device-scene__app-icon">
            <Image alt="" height={76} src="/assets/levik-logo.webp" width={76} />
          </div>
          <span className="device-scene__status-label">Статус</span>
          <strong className="device-scene__status">Подключено</strong>
          <div className="device-scene__connected">
            <CheckIcon height={42} width={42} />
          </div>
          <div aria-hidden="true" className="device-scene__bars">
            {Array.from({ length: 10 }, (_, index) => (
              <span key={index} />
            ))}
          </div>
        </div>

        <div className="hero-badge hero-badge--signal">
          <SignalIcon />
          Стабильное LTE
        </div>
        <div className="hero-badge hero-badge--security">
          <ShieldCheckIcon />
          Всё под контролем
        </div>
        <div className="hero-badge hero-badge--support">
          <SupportIcon />
          Поможем с настройкой
        </div>
      </div>
    </div>
  );
}
