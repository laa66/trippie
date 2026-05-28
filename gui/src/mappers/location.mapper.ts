import type { LocationDto } from "@/types/api/location.dto";
import type { Location } from "@/types/models/location";

export class LocationMapper {
    
    static toModel(dto: LocationDto): Location {
        return {
            name: dto.name,
            latitude: dto.latitude,
            longitude: dto.longitude,
        };
    }
}