//
//
//  Copyright © 2020 Mobile Technology Solutions, Inc. All rights reserved.
//

import Foundation

extension Data {
    var hex: String {
        return map { String(format: "%02hhX", $0) }.joined()
    }
    var utf8: String? {
        return String(bytes: self, encoding: String.Encoding.utf8)
    }
}

extension String {
    var isASCII20to5F: Bool {
        var asciiCharacters0x20to0x5F = String()
        for i in 32...127 {
            let v = String(format: "%c", i)
            asciiCharacters0x20to0x5F.append(v)
        }
        let allowedCharacterSet = NSCharacterSet(charactersIn: asciiCharacters0x20to0x5F)
        let selfSet = CharacterSet(charactersIn: self)
        return allowedCharacterSet.isSuperset(of: selfSet)
    }
}

extension OutputStream {
    func write(_ data: Data) -> Int {
        return data.withUnsafeBytes({ (rawBufferPointer: UnsafeRawBufferPointer) -> Int in
            let bufferPointer = rawBufferPointer.bindMemory(to: UInt8.self)
            return self.write(bufferPointer.baseAddress!, maxLength: data.count)
        })
    }
}
