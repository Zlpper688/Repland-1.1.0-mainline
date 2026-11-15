export function MountainWash({ className = '' }: { className?: string }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 480 210"
      preserveAspectRatio="xMidYMax slice"
      className={`pointer-events-none select-none ${className}`}
    >
      <defs>
        <linearGradient id="mist" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#ffffff" stopOpacity="0.55" />
          <stop offset="100%" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
        <linearGradient id="ridge-a" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#8fae95" stopOpacity="0.42" />
          <stop offset="100%" stopColor="#8fae95" stopOpacity="0.08" />
        </linearGradient>
        <linearGradient id="ridge-b" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#9b8cc0" stopOpacity="0.30" />
          <stop offset="100%" stopColor="#9b8cc0" stopOpacity="0.04" />
        </linearGradient>
        <linearGradient id="ridge-c" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#55705b" stopOpacity="0.30" />
          <stop offset="100%" stopColor="#55705b" stopOpacity="0.06" />
        </linearGradient>
      </defs>
      <circle cx="370" cy="58" r="30" fill="#e8c9a8" opacity="0.45" />
      <path d="M-20 164 L70 78 L132 128 L196 64 L268 142 L330 98 L420 156 L500 118 L500 210 L-20 210 Z" fill="url(#ridge-b)" />
      <path d="M-20 178 L54 116 L112 158 L182 96 L250 158 L316 120 L382 168 L470 132 L500 150 L500 210 L-20 210 Z" fill="url(#ridge-a)" />
      <path d="M-20 192 L48 150 L118 184 L190 142 L260 184 L330 152 L400 190 L468 162 L500 176 L500 210 L-20 210 Z" fill="url(#ridge-c)" />
      <rect x="0" y="150" width="480" height="44" fill="url(#mist)" opacity="0.5" />
      <g stroke="#7c8d80" strokeWidth="1.4" strokeLinecap="round" opacity="0.5">
        <path d="M96 62 q7 -6 14 0" fill="none" />
        <path d="M112 52 q6 -5 12 0" fill="none" />
        <path d="M412 82 q6 -5 12 0" fill="none" />
      </g>
    </svg>
  );
}
