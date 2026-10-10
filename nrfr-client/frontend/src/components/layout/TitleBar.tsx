import React, {useState} from 'react';
import logo from '../../assets/images/logo.png';
import {WindowClose, WindowMaximise, WindowMinimise} from '../../../wailsjs/go/main/App';
import {BrowserOpenURL} from '../../../wailsjs/runtime/runtime';

export const TitleBar: React.FC = () => {
    const [showAbout, setShowAbout] = useState(false);

    const handleOpenProject = () => {
        BrowserOpenURL('https://github.com/alzpqm/Nrfr');
    };

    return (
        <div className="relative">
            <div className="bg-white/30 backdrop-blur-md h-8 flex items-center px-4"
                 style={{"--wails-draggable": "drag"} as React.CSSProperties}>
                <div className="flex-1 flex items-center space-x-2">
                    <img src={logo} className="w-4 h-4" alt="Nrfr 圖示"/>
                    <div className="text-sm font-semibold text-gray-700">Nrfr - 快速啟動工具</div>
                </div>
                <div className="flex space-x-2" style={{"--wails-draggable": "no-drag"} as React.CSSProperties}>
                    <button
                        className="text-gray-600 hover:text-gray-800 px-2"
                        onClick={() => setShowAbout(!showAbout)}
                        title="關於"
                        aria-label="打開關於頁面"
                    >
                        <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20"
                             fill="currentColor">
                            <path fillRule="evenodd"
                                  d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z"
                                  clipRule="evenodd"/>
                        </svg>
                    </button>
                    <button className="window-control-button bg-yellow-400 hover:bg-yellow-500" title="最小化"
                            aria-label="最小化視窗" onClick={() => WindowMinimise()}></button>
                    <button className="window-control-button bg-green-400 hover:bg-green-500" title="最大化"
                            aria-label="最大化視窗" onClick={() => WindowMaximise()}></button>
                    <button className="window-control-button bg-red-400 hover:bg-red-500" title="關閉"
                            aria-label="關閉視窗" onClick={() => WindowClose()}></button>
                </div>
            </div>

            {showAbout && (
                <div
                    className="fixed right-2 top-10 w-72 bg-white/95 backdrop-blur-md rounded-xl shadow-2xl border border-gray-100 p-4 z-50"
                    style={{"--wails-draggable": "no-drag"} as React.CSSProperties}>
                    <div className="space-y-3">
                        <button
                            onClick={() => setShowAbout(false)}
                            className="absolute right-2 top-2 text-gray-400 hover:text-gray-600 transition-colors"
                            title="關閉"
                            aria-label="關閉關於頁面"
                        >
                            <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" fill="none" viewBox="0 0 24 24"
                                 stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                                      d="M6 18L18 6M6 6l12 12"/>
                            </svg>
                        </button>

                        <div className="bg-blue-50/50 rounded-lg p-3 space-y-2">
                            <p className="text-gray-700 font-medium">Nrfr · Android 17 相容版</p>
                            <p className="text-sm text-gray-600">由 Nrfr 維護團隊維護，透過 Shizuku 免 root 調整 SIM 設定。</p>
                            <button
                                onClick={handleOpenProject}
                                className="flex items-center space-x-1.5 text-blue-500 hover:text-blue-600 transition-colors"
                            >
                                <span className="font-medium">專案首頁</span>
                            </button>
                            <button
                                onClick={() => BrowserOpenURL('https://github.com/alzpqm/Nrfr/releases')}
                                className="block text-blue-500 hover:text-blue-600 transition-colors"
                            >下載版本</button>
                            <button
                                onClick={() => BrowserOpenURL('https://github.com/alzpqm/Nrfr/issues')}
                                className="block text-blue-500 hover:text-blue-600 transition-colors"
                            >回報問題</button>
                            <p className="text-xs text-gray-500">基於 Antkites 的 Ackites/Nrfr，採用 Apache License 2.0。</p>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};
