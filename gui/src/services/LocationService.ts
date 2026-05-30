import type { LocationDto } from '@/types/api/location.dto';
import { BaseService } from './BaseService';
import type { Location } from '@/types/models/location';
import { LocationMapper } from '@/mappers/location.mapper';

export class LocationService extends BaseService {

    protected baseUrl = 'http://localhost:8080/api/v1/spatial';

    // TODO: Test purpose only
    async getAllLocations(): Promise<Location[]> {
        const locationDtos = await this.get<LocationDto[]>(`${this.baseUrl}/location`);
        return locationDtos.map(dto => LocationMapper.toModel(dto));
    }

}